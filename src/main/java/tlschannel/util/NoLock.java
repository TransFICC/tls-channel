package tlschannel.util;

public final class NoLock implements Lock {
  public static final Lock INSTANCE = new NoLock();

  private NoLock() {}

  @Override
  public void lock() {}

  @Override
  public void unlock() {}

  @Override
  public boolean tryLock() {
    return true;
  }
}
