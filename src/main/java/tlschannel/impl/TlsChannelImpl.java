package tlschannel.impl;

import static javax.net.ssl.SSLEngineResult.HandshakeStatus.FINISHED;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_UNWRAP;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Optional;
import java.util.function.Consumer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tlschannel.NeedsHandshakeException;
import tlschannel.NeedsReadException;
import tlschannel.NeedsTaskException;
import tlschannel.NeedsWriteException;
import tlschannel.TlsChannelCallbackException;
import tlschannel.TrackingAllocator;
import tlschannel.WouldBlockException;
import tlschannel.util.Lock;
import tlschannel.util.LockFactory;
import tlschannel.util.Util;

public class TlsChannelImpl implements ByteChannel {

  private static final Logger logger = LoggerFactory.getLogger(TlsChannelImpl.class);

  public static final int buffersInitialSize = 4096;

  /** Official TLS max data size is 2^14 = 16k. Use 1024 more to account for the overhead */
  public static final int maxTlsPacketSize = 17 * 1024;

  private ImmutableSingleBufferSupplierSet inPlainBufferSet;

  private static class UnwrapResult {
    public int bytesProduced;
    public HandshakeStatus lastHandshakeStatus;
    public boolean wasClosed;

    private UnwrapResult of(
        int bytesProduced, HandshakeStatus lastHandshakeStatus, boolean wasClosed) {
      this.bytesProduced = bytesProduced;
      this.lastHandshakeStatus = lastHandshakeStatus;
      this.wasClosed = wasClosed;
      return this;
    }
  }

  private static class WrapResult {
    public int bytesConsumed;
    public HandshakeStatus lastHandshakeStatus;

    private WrapResult of(int bytesConsumed, HandshakeStatus lastHandshakeStatus) {
      this.bytesConsumed = bytesConsumed;
      this.lastHandshakeStatus = lastHandshakeStatus;
      return this;
    }
  }

  /** Used to signal EOF conditions from the underlying channel */
  public static class EofException extends Exception {

    /** For efficiency, override this method to do nothing. */
    @Override
    public Throwable fillInStackTrace() {
      return this;
    }
  }

  private final ReadableByteChannel readChannel;
  private final WritableByteChannel writeChannel;
  private final SSLEngine engine;
  private final boolean explicitHandshake;
  private BufferHolder inEncrypted;
  private final Consumer<SSLSession> initSessionCallback;

  private final boolean runTasks;
  private final TrackingAllocator encryptedBufAllocator;
  private final TrackingAllocator plainBufAllocator;
  private final boolean waitForCloseConfirmation;
  private final MutableSingleBufferSet mutableSingleBufferSetRead = new MutableSingleBufferSet();
  private final MutableByteBufferSet mutableBufferSetRead = new MutableByteBufferSet();

  private final MutableSingleBufferSet mutableSingleBufferSetWrite = new MutableSingleBufferSet();
  private final MutableByteBufferSet mutableBufferSetWrite = new MutableByteBufferSet();

  // @formatter:off
  public TlsChannelImpl(
      ReadableByteChannel readChannel,
      WritableByteChannel writeChannel,
      SSLEngine engine,
      Optional<BufferHolder> inEncrypted,
      Consumer<SSLSession> initSessionCallback,
      boolean runTasks,
      TrackingAllocator plainBufAllocator,
      TrackingAllocator encryptedBufAllocator,
      boolean releaseBuffers,
      boolean waitForCloseConfirmation,
      LockFactory lockFactory,
      boolean explicitHandshake) {
    // @formatter:on
    this.readChannel = readChannel;
    this.writeChannel = writeChannel;
    this.engine = engine;
    this.explicitHandshake = explicitHandshake;
    this.inEncrypted =
        inEncrypted.orElseGet(
            () ->
                new BufferHolder(
                    "inEncrypted",
                    Optional.empty(),
                    encryptedBufAllocator,
                    buffersInitialSize,
                    maxTlsPacketSize,
                    false /* plainData */,
                    releaseBuffers));
    this.initSessionCallback = initSessionCallback;
    this.runTasks = runTasks;
    this.plainBufAllocator = plainBufAllocator;
    this.encryptedBufAllocator = encryptedBufAllocator;
    this.waitForCloseConfirmation = waitForCloseConfirmation;
    inPlain =
        new BufferHolder(
            "inPlain",
            Optional.empty(),
            plainBufAllocator,
            buffersInitialSize,
            maxTlsPacketSize,
            true /* plainData */,
            releaseBuffers);

    inPlainBufferSet = new ImmutableSingleBufferSupplierSet(() -> inPlain.prepare());
    outEncrypted =
        new BufferHolder(
            "outEncrypted",
            Optional.empty(),
            encryptedBufAllocator,
            buffersInitialSize,
            maxTlsPacketSize,
            false /* plainData */,
            releaseBuffers);
    initLock = lockFactory.newLock();
    readLock = lockFactory.newLock();
    writeLock = lockFactory.newLock();
    wrapResult = new WrapResult();
    unwrapResult = new UnwrapResult();
  }

  private final Lock initLock;
  private final Lock readLock;
  private final Lock writeLock;
  private final WrapResult wrapResult;
  private final UnwrapResult unwrapResult;
  private volatile boolean negotiated = false;
  private volatile boolean isHandshaking = false;

  /**
   * Whether a IOException was received from the underlying channel or from the {@link SSLEngine}.
   */
  private volatile boolean invalid = false;

  /** Whether a close_notify was already sent. */
  private volatile boolean shutdownSent = false;

  /** Whether a close_notify was already received. */
  private volatile boolean shutdownReceived = false;

  // decrypted data from inEncrypted
  private BufferHolder inPlain;

  // contains data encrypted to send to the underlying channel
  private BufferHolder outEncrypted;

  /**
   * Handshake wrap() method calls need a buffer to read from, even when they actually do not read
   * anything.
   *
   * <p>Note: standard SSLEngine is happy with no buffers, the empty buffer is here to make this
   * work with Netty's OpenSSL's wrapper.
   */
  private final ByteBufferSet dummyOut =
      new ImmutableByteBufferSet(new ByteBuffer[] {ByteBuffer.allocate(0)});

  public Consumer<SSLSession> getSessionInitCallback() {
    return initSessionCallback;
  }

  public TrackingAllocator getPlainBufferAllocator() {
    return plainBufAllocator;
  }

  public TrackingAllocator getEncryptedBufferAllocator() {
    return encryptedBufAllocator;
  }

  public int doWorkLoop(final ByteBufferSet dest, final boolean force)
      throws IOException, EofException {

    int result = doWork(dest, force);
    while (true) {
      if (result >= 0) {
        return result;
      }
      result = doWork(dest, false);
    }
  }

  public int doWork(final ByteBufferSet dest, final boolean force)
      throws IOException, EofException {

    //    if (!explicitHandshake) {
    //      throw new UnsupportedOperationException("This is only used when handshaking is being
    // manually invoked");
    //    }
    //    if (negotiated) {
    //      return 0;
    //    }
    int bytesRead = -1;
    try {
      if (!isHandshaking) {
        if (force || !negotiated) {
          logger.trace("Called engine.beginHandshake()");
          engine.beginHandshake();
        }

        Util.assertTrue(inPlain.nullOrEmpty());
        //        outEncrypted.prepare();
        writeToChannel(); // Is this needed????
        isHandshaking = true;
      }
      bytesRead = maybeHandshakeStep(dest);

      if (bytesRead >= 0) {
        negotiated = true;
        isHandshaking = false;
      }
      return bytesRead;

    } finally {
      if (bytesRead >= 0) {
        //        outEncrypted.release();
      }
    }
  }

  // read

  public long read(ByteBuffer[] dstBuffers, int offset, int length) throws IOException {
    if (ByteBufferSetUtil.isReadOnly(dstBuffers, offset, length)) {
      throw new IllegalArgumentException();
    }
    if (ByteBufferSetUtil.remaining(dstBuffers, offset, length) <= 0) {
      return 0L;
    }
    handshakeAndReadLock();
    return readAndUnlock(mutableBufferSetRead.wrap(dstBuffers, offset, length));
  }

  @Override
  public int read(ByteBuffer dstBuffer) throws IOException {
    if (dstBuffer.isReadOnly()) {
      throw new IllegalArgumentException();
    }
    if (dstBuffer.remaining() <= 0) {
      return 0;
    }
    handshakeAndReadLock();
    return (int) readAndUnlock(mutableSingleBufferSetRead.wrap(dstBuffer));
  }

  private long readAndUnlock(final ByteBufferSet dest) throws IOException {
    try {
      if (invalid || shutdownSent) {
        throw new ClosedChannelException();
      }
      HandshakeStatus handshakeStatus = engine.getHandshakeStatus();
      throwIfExplicitHandshakeRequired(handshakeStatus);
      int bytesToReturn = inPlain.nullOrEmpty() ? 0 : inPlain.buffer.position();
      while (true) {
        if (bytesToReturn > 0) {
          if (inPlain.nullOrEmpty()) {
            return bytesToReturn;
          } else {
            return transferPendingPlain(dest);
          }
        }
        if (shutdownReceived) {
          return -1;
        }
        Util.assertTrue(inPlain.nullOrEmpty());
        switch (handshakeStatus) {
          case NEED_UNWRAP:
          case NEED_WRAP:
            bytesToReturn = handshake(dest, false);
            handshakeStatus = NOT_HANDSHAKING;
            break;
          case NOT_HANDSHAKING:
          case FINISHED:
            UnwrapResult res = readAndUnwrap(dest);
            if (res.wasClosed) {
              return -1;
            }
            bytesToReturn = res.bytesProduced;
            handshakeStatus = res.lastHandshakeStatus;
            break;
          case NEED_TASK:
            handleTask();
            handshakeStatus = engine.getHandshakeStatus();
            break;
        }
      }
    } catch (EofException e) {
      return -1;
    } finally {
      readLock.unlock();
    }
  }

  private void throwIfExplicitHandshakeRequired(final HandshakeStatus handshakeStatus)
      throws NeedsHandshakeException {
    if (handshakeStatus != NOT_HANDSHAKING
        && handshakeStatus != HandshakeStatus.FINISHED
        && explicitHandshake) {
      throw new NeedsHandshakeException();
    }
  }

  private void handshakeAndReadLock() throws IOException {
    if (!explicitHandshake) {
      handshake();
    }
    readLock.lock();
  }

  private void handleTask() throws NeedsTaskException {
    if (runTasks) {
      engine.getDelegatedTask().run();
    } else {
      throw new NeedsTaskException(engine.getDelegatedTask());
    }
  }

  private int transferPendingPlain(ByteBufferSet dstBuffers) {
    inPlain.buffer.flip(); // will read
    int bytes = dstBuffers.putRemaining(inPlain.buffer);
    inPlain.buffer.compact(); // will write
    boolean disposed = inPlain.release();
    if (!disposed) {
      inPlain.zeroRemaining();
    }
    return bytes;
  }

  private UnwrapResult unwrapLoop(ByteBufferSet dest, HandshakeStatus originalStatus)
      throws SSLException {
    ByteBufferSet effDest = dest;

    while (true) {
      Util.assertTrue(inPlain.nullOrEmpty());
      SSLEngineResult result = callEngineUnwrap(effDest);
      /*
       * Note that data can be returned even in case of overflow, in that
       * case, just return the data.
       */
      if (result.bytesProduced() > 0
          || result.getStatus() == Status.BUFFER_UNDERFLOW
          || result.getStatus() == Status.CLOSED
          || result.getHandshakeStatus() != originalStatus) {
        boolean wasClosed = result.getStatus() == Status.CLOSED;
        return unwrapResult.of(result.bytesProduced(), result.getHandshakeStatus(), wasClosed);
      }
      if (result.getStatus() == Status.BUFFER_OVERFLOW) {
        inPlain.prepare();
        ensureInPlainCapacity(Math.min(((int) effDest.remaining()) * 2, maxTlsPacketSize));
      }
      // inPlain changed, re-create the wrapper
      effDest = inPlainBufferSet;
    }
  }

  private SSLEngineResult callEngineUnwrap(ByteBufferSet dest) throws SSLException {
    inEncrypted.buffer.flip();
    try {
      final SSLEngineResult result = dest.unwrap(engine, inEncrypted.buffer);

      if (logger.isTraceEnabled()) {
        logger.trace(
            "engine.unwrap() result [{}]. Engine status: {}; inEncrypted {}; inPlain: {}",
            Util.resultToString(result),
            result.getHandshakeStatus(),
            inEncrypted,
            dest);
      }
      return result;
    } catch (SSLException e) {
      // something bad was received from the underlying channel, we cannot
      // continue
      invalid = true;
      throw e;
    } finally {
      inEncrypted.buffer.compact();
    }
  }

  private int readFromChannel() throws IOException, EofException {
    try {
      return readFromChannel(readChannel, inEncrypted.buffer);
    } catch (WouldBlockException e) {
      throw e;
    } catch (IOException e) {
      invalid = true;
      throw e;
    }
  }

  public static int readFromChannel(ReadableByteChannel readChannel, ByteBuffer buffer)
      throws IOException, EofException {
    Util.assertTrue(buffer.hasRemaining());
    logger.trace("Reading from channel");
    int c = readChannel.read(buffer); // IO block
    logger.trace("Read from channel; response: {}, buffer: {}", c, buffer);
    if (c == -1) {
      throw new EofException();
    }
    if (c == 0) {
      throw new NeedsReadException();
    }
    return c;
  }

  // write

  public long write(ByteBuffer[] srcBuffers, int offset, int length) throws IOException {
    handshakeAndWriteLock();
    return writeAndUnlock(mutableBufferSetWrite.wrap(srcBuffers, offset, length));
  }

  public long write(ByteBuffer[] outs) throws IOException {
    return write(outs, 0, outs.length);
  }

  @Override
  public int write(ByteBuffer srcBuffer) throws IOException {
    handshakeAndWriteLock();
    return (int) writeAndUnlock(mutableSingleBufferSetWrite.wrap(srcBuffer));
  }

  public long write(ByteBufferSet source) throws IOException {
    /*
     * Note that we should enter the write loop even in the case that the source buffer has no remaining bytes,
     * as it could be the case, in non-blocking usage, that the user is forced to call write again after the
     * underlying channel is available for writing, just to write pending encrypted bytes.
     */
    handshakeAndWriteLock();
    return writeAndUnlock(source);
  }

  private long writeAndUnlock(final ByteBufferSet source) throws IOException {
    try {
      if (invalid || shutdownSent) {
        throw new ClosedChannelException();
      }
      HandshakeStatus handshakeStatus = engine.getHandshakeStatus();
      throwIfExplicitHandshakeRequired(handshakeStatus);
      return wrapAndWrite(source);
    } finally {
      writeLock.unlock();
    }
  }

  private void handshakeAndWriteLock() throws IOException {
    if (!explicitHandshake) {
      handshake();
    }
    writeLock.lock();
  }

  private long wrapAndWrite(ByteBufferSet source) throws IOException {
    long bytesToConsume = source.remaining();
    long bytesConsumed = 0;
    outEncrypted.prepare();
    try {
      while (true) {
        writeToChannel();
        if (bytesConsumed == bytesToConsume) {
          return bytesToConsume;
        }
        WrapResult res = wrapLoop(source);
        bytesConsumed += res.bytesConsumed;
      }
    } finally {
      outEncrypted.release();
    }
  }

  private WrapResult wrapLoop(ByteBufferSet source) throws SSLException {
    while (true) {
      SSLEngineResult result = callEngineWrap(source);
      switch (result.getStatus()) {
        case OK:
        case CLOSED:
          return wrapResult.of(result.bytesConsumed(), result.getHandshakeStatus());
        case BUFFER_OVERFLOW:
          Util.assertTrue(result.bytesConsumed() == 0);
          outEncrypted.enlarge();
          break;
        case BUFFER_UNDERFLOW:
          throw new IllegalStateException();
      }
    }
  }

  private SSLEngineResult callEngineWrap(ByteBufferSet source) throws SSLException {
    try {
      final SSLEngineResult result = source.wrap(engine, outEncrypted.buffer);
      if (logger.isTraceEnabled()) {
        logger.trace(
            "engine.wrap() result: [{}]; engine status: {}; srcBuffer: {}, outEncrypted: {}",
            Util.resultToString(result),
            result.getHandshakeStatus(),
            source,
            outEncrypted);
      }
      return result;
    } catch (SSLException e) {
      invalid = true;
      throw e;
    }
  }

  private void ensureInPlainCapacity(int newCapacity) {
    if (inPlain.buffer.capacity() < newCapacity) {
      logger.trace(
          "inPlain buffer too small, increasing from {} to {}",
          inPlain.buffer.capacity(),
          newCapacity);
      inPlain.resize(newCapacity);
    }
  }

  private void writeToChannel() throws IOException {
    if (outEncrypted.buffer.position() == 0) {
      return;
    }
    outEncrypted.buffer.flip();
    try {
      try {
        writeToChannel(writeChannel, outEncrypted.buffer);
      } catch (WouldBlockException e) {
        throw e;
      } catch (IOException e) {
        invalid = true;
        throw e;
      }
    } finally {
      outEncrypted.buffer.compact();
    }
  }

  private static void writeToChannel(WritableByteChannel channel, ByteBuffer src)
      throws IOException {
    while (src.hasRemaining()) {
      logger.trace("Writing to channel: {}", src);
      int c = channel.write(src);
      if (c == 0) {
        /*
         * If no bytesProduced were written, it means that the socket is
         * non-blocking and needs more buffer space, so stop the loop
         */
        throw new NeedsWriteException();
      }
      // blocking SocketChannels can write less than all the bytesProduced
      // just before an error the loop forces the exception
    }
  }

  // handshake and close

  /**
   * Force a new negotiation.
   *
   * @throws IOException if the underlying channel throws an IOException
   */
  public void renegotiate() throws IOException {
    /*
     * Renegotiation was removed in TLS 1.3. We have to do the check at this level because SSLEngine will not
     * check that, and just enter into undefined behavior.
     */
    // relying in hopefully-robust lexicographic ordering of protocol names
    if (engine.getSession().getProtocol().compareTo("TLSv1.3") >= 0) {
      throw new SSLException("renegotiation not supported in TLS 1.3 or latter");
    }
    try {
      doHandshake(true /* force */);
    } catch (EofException e) {
      throw new ClosedChannelException();
    }
  }

  /**
   * Do a negotiation if this connection is new and it hasn't been done already.
   *
   * @throws IOException if the underlying channel throws an IOException
   */
  public void handshake() throws IOException {
    try {
      doHandshake(false /* force */);
    } catch (EofException e) {
      throw new ClosedChannelException();
    }
  }

  private void doHandshake(boolean force) throws IOException, EofException {
    if (!force && negotiated) {
      return;
    }
    initLock.lock();
    if (invalid || shutdownSent) {
      throw new ClosedChannelException();
    }
    try {
      if (force || !negotiated) {
        //                engine.beginHandshake();
        handshake(this.inPlainBufferSet, force);
        // call client code
        try {
          initSessionCallback.accept(engine.getSession());
        } catch (Exception e) {
          logger.trace("client code threw exception in session initialization callback", e);
          throw new TlsChannelCallbackException("session initialization callback failed", e);
        }
        negotiated = true;
      }
    } finally {
      initLock.unlock();
    }
  }

  private int handshake(ByteBufferSet dest, final boolean force) throws IOException, EofException {

    readLock.lock();
    try {
      writeLock.lock();
      try {
        //        Util.assertTrue(inPlain.nullOrEmpty());
        outEncrypted.prepare();
        try {
          //          writeToChannel(); // IO block
          return doWorkLoop(dest, force);
          //          return handshakeLoop(dest);
        } finally {
          outEncrypted.release();
        }
      } finally {
        writeLock.unlock();
      }
    } finally {
      readLock.unlock();
    }
  }

  private int handshakeLoop(ByteBufferSet dest) throws IOException, EofException {
    Util.assertTrue(inPlain.nullOrEmpty());
    while (true) {

      final int bytesRead = maybeHandshakeStep(dest);
      if (bytesRead >= 0) return bytesRead;
    }
  }

  private int maybeHandshakeStep(final ByteBufferSet dest) throws IOException, EofException {
    final HandshakeStatus status = engine.getHandshakeStatus();
    if (status == FINISHED || status == NOT_HANDSHAKING) {
      return 0;
    }

    final HandshakeStatus newStatus = handshakeStep(dest, status);

    if (newStatus == NEED_UNWRAP && unwrapResult.bytesProduced > 0) {
      return unwrapResult.bytesProduced;
    }
    return -2;
  }

  private HandshakeStatus handshakeStep(final ByteBufferSet dest, final HandshakeStatus status)
      throws IOException, EofException {
    switch (status) {
      case NEED_WRAP:
        Util.assertTrue(outEncrypted.nullOrEmpty());
        wrapLoop(dummyOut);
        writeToChannel(); // IO block
        break;
      case NEED_UNWRAP:
        readAndUnwrap(dest);
        break;
      case NOT_HANDSHAKING:
        /*
         * This should not really happen using SSLEngine, because
         * handshaking ends with a FINISHED status. However, we accept
         * this value to permit the use of a pass-through stub engine
         * with no encryption.
         */
        break;
      case NEED_TASK:
        handleTask();
        break;
      case FINISHED:
        break;
    }
    return engine.getHandshakeStatus();
  }

  private UnwrapResult readAndUnwrap(ByteBufferSet dest) throws IOException, EofException {
    // Save status before operation: use it to stop when status changes
    HandshakeStatus orig = engine.getHandshakeStatus();
    inEncrypted.prepare();
    try {
      while (true) {
        Util.assertTrue(inPlain.nullOrEmpty());
        UnwrapResult res = unwrapLoop(dest, orig);
        if (res.bytesProduced > 0 || res.lastHandshakeStatus != orig || res.wasClosed) {
          if (res.wasClosed) {
            shutdownReceived = true;
          }
          return res;
        }
        if (!inEncrypted.buffer.hasRemaining()) {
          inEncrypted.enlarge();
        }
        readFromChannel(); // IO block
      }
    } finally {
      inEncrypted.release();
    }
  }

  public void close() throws IOException {
    tryShutdown();
    writeChannel.close();
    readChannel.close();
    /*
     * After closing the underlying channels, locks should be taken fast.
     */
    readLock.lock();
    try {
      writeLock.lock();
      try {
        freeBuffers();
      } finally {
        writeLock.unlock();
      }
    } finally {
      readLock.unlock();
    }
  }

  private void tryShutdown() {
    if (!readLock.tryLock()) {
      return;
    }
    try {
      if (!writeLock.tryLock()) {
        return;
      }
      try {
        if (!shutdownSent) {
          try {
            boolean closed = shutdown();
            if (!closed && waitForCloseConfirmation) {
              shutdown();
            }
          } catch (Throwable e) {
            logger.debug("error doing TLS shutdown on close(), continuing: {}", e.getMessage());
          }
        }
      } finally {
        writeLock.unlock();
      }
    } finally {
      readLock.unlock();
    }
  }

  public boolean shutdown() throws IOException {
    readLock.lock();
    try {
      writeLock.lock();
      try {
        if (invalid) {
          throw new ClosedChannelException();
        }
        if (!shutdownSent) {
          shutdownSent = true;
          outEncrypted.prepare();
          try {
            writeToChannel(); // IO block
            engine.closeOutbound();
            wrapLoop(dummyOut);
            writeToChannel(); // IO block
          } finally {
            outEncrypted.release();
          }
          /*
           * If this side is the first to send close_notify, then,
           * inbound is not done and false should be returned (so the
           * client waits for the response). If this side is the
           * second, then inbound was already done, and we can return
           * true.
           */
          if (shutdownReceived) {
            freeBuffers();
          }
          return shutdownReceived;
        }
        /*
         * If we reach this point, then we just have to read the close
         * notification from the client. Only try to do it if necessary,
         * to make this method idempotent.
         */
        if (!shutdownReceived) {
          try {
            // IO block
            readAndUnwrap(this.inPlainBufferSet);
            Util.assertTrue(shutdownReceived);
          } catch (EofException e) {
            throw new ClosedChannelException();
          }
        }
        freeBuffers();
        return true;
      } finally {
        writeLock.unlock();
      }
    } finally {
      readLock.unlock();
    }
  }

  private void freeBuffers() {
    if (inEncrypted != null) {
      inEncrypted.dispose();
      inEncrypted = null;
    }
    if (inPlain != null) {
      inPlain.dispose();
      inPlain = null;
      inPlainBufferSet = null;
    }
    if (outEncrypted != null) {
      outEncrypted.dispose();
      outEncrypted = null;
    }
  }

  public boolean isOpen() {
    return !invalid && writeChannel.isOpen() && readChannel.isOpen();
  }

  public static void checkReadBuffer(ByteBuffer dest) {
    if (dest.isReadOnly()) {
      throw new IllegalArgumentException();
    }
  }

  public static void checkReadBuffers(ByteBuffer[] dest, int offset, int length) {
    for (int i = offset; i < length; i++) {
      if (dest[i].isReadOnly()) {
        throw new IllegalArgumentException();
      }
    }
  }

  public SSLEngine engine() {
    return engine;
  }

  public boolean getRunTasks() {
    return runTasks;
  }

  public boolean shutdownReceived() {
    return shutdownReceived;
  }

  public boolean shutdownSent() {
    return shutdownSent;
  }

  public ReadableByteChannel plainReadableChannel() {
    return readChannel;
  }

  public WritableByteChannel plainWritableChannel() {
    return writeChannel;
  }
}
