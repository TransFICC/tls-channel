package tlschannel.util;

public interface Lock {
  void lock();

  void unlock();

  boolean tryLock();
}
