package tlschannel.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Supplier;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import tlschannel.TlsChannel;

public class ImmutableSingleBufferSupplierSet implements ByteBufferSet {
  private final Supplier<ByteBuffer> bufferSupplier;

  public ImmutableSingleBufferSupplierSet(final Supplier<ByteBuffer> byteBuffer) {
    this.bufferSupplier = byteBuffer;
  }

  @Override
  public long remaining() {
    return get().remaining();
  }

  @Override
  public int putRemaining(final ByteBuffer from) {
    int bytes = Math.min(from.remaining(), get().remaining());
    ByteBufferUtil.copy(from, bufferSupplier.get(), bytes);
    return bytes;
  }

  @Override
  public ByteBufferSet put(final ByteBuffer from, final int length) {
    if (from.remaining() < length) {
      throw new IllegalArgumentException();
    }
    if (get().remaining() < length) {
      throw new IllegalArgumentException();
    }
    if (length != 0) {
      int bytes = Math.min(length, from.remaining());
      ByteBufferUtil.copy(from, get(), bytes);
    }
    return this;
  }

  @Override
  public int getRemaining(final ByteBuffer dst) {
    if (!dst.hasRemaining()) {
      return 0;
    }
    int bytes = Math.min(dst.remaining(), get().remaining());
    ByteBufferUtil.copy(bufferSupplier.get(), dst, bytes);
    return bytes;
  }

  @Override
  public ByteBufferSet get(final ByteBuffer dst, final int length) {
    final ByteBuffer byteBuffer = get();
    if (byteBuffer.remaining() < length) {
      throw new IllegalArgumentException();
    }
    if (dst.remaining() < length) {
      throw new IllegalArgumentException();
    }
    if (length != 0) {
      ByteBufferUtil.copy(byteBuffer, dst, Math.min(length, byteBuffer.remaining()));
    }
    return this;
  }

  @Override
  public boolean hasRemaining() {
    return get().hasRemaining();
  }

  @Override
  public boolean isReadOnly() {
    return get().isReadOnly();
  }

  @Override
  public SSLEngineResult unwrap(final SSLEngine engine, final ByteBuffer buffer)
      throws SSLException {
    return engine.unwrap(buffer, get());
  }

  @Override
  public SSLEngineResult wrap(final SSLEngine engine, final ByteBuffer buffer) throws SSLException {
    return engine.wrap(get(), buffer);
  }

  @Override
  public long read(final TlsChannel tlsChannel) throws IOException {
    return tlsChannel.read(get());
  }

  @Override
  public void write(final TlsChannel tlsChannel) throws IOException {
    tlsChannel.write(get());
  }

  private ByteBuffer get() {
    return bufferSupplier.get();
  }
}
