package tlschannel.util;

public class ReentrantLock implements Lock {
  private final java.util.concurrent.locks.ReentrantLock actualLock =
      new java.util.concurrent.locks.ReentrantLock();

  @Override
  public void lock() {
    actualLock.lock();
  }

  @Override
  public void unlock() {
    actualLock.unlock();
  }

  @Override
  public boolean tryLock() {
    return actualLock.tryLock();
  }
}
