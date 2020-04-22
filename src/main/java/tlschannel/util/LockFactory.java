package tlschannel.util;

@FunctionalInterface
public interface LockFactory {
  Lock newLock();
}
