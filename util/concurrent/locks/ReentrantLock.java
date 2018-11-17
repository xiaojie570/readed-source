
package java.util.concurrent.locks;
import java.util.concurrent.TimeUnit;
import java.util.Collection;

public class ReentrantLock implements Lock, java.io.Serializable {
    private static final long serialVersionUID = 7373984872572414699L;
    //实现抽象类AQS的内部类
    private final Sync sync;

    abstract static class Sync extends AbstractQueuedSynchronizer {
        // 序列号
		private static final long serialVersionUID = -5179523762034025860L;

		// 获取锁
		// 作用：锁定，并未实现，留给具体子类实现，这里留给NonfairSync和FairSync类实现
        abstract void lock();

		// 非公平方式获取
		/**
		* 1. 首先获取这个锁的状态，如果状态为0，则尝试设置状态为传入的参数，若设置成功就代表自己获取到了锁，返回true
		* 2. 如果状态不是0，则判定当前线程是否为排它锁的Owner，如果是则尝试将状态增加acquires。如果这个状态值越界，则会抛出异常提示
		* 3. 如果状态不是0，且自身不是Owner，则返回false。
		*/
        final boolean nonfairTryAcquire(int acquires) {
            // 当前线程
			final Thread current = Thread.currentThread();
            // 获取状态
			int c = getState();
            // 状态为0表示没有线程正在竞争该锁
			if (c == 0) {
				// 比较并设置状态成功，状态0表示锁没有被占用
                if (compareAndSetState(0, acquires)) {
					// 设置当前线程独占
                    setExclusiveOwnerThread(current);
                    return true;// 成功
                }
            }
			// 当前线程拥有该锁
            else if (current == getExclusiveOwnerThread()) {
                // 增加重入次数
				int nextc = c + acquires;
                if (nextc < 0) // overflow
                    throw new Error("Maximum lock count exceeded");
                // 设置状态
				setState(nextc);
                return true;
            }
            return false;
        }
		
		// 试图在独占模式下获取对象状态，此方法应该查询是否允许它在共享模式下获取对象状态，如果允许则获取它
        protected final boolean tryRelease(int releases) {
            int c = getState() - releases;
			// 当前线程不是独占模式，则抛出异常
            if (Thread.currentThread() != getExclusiveOwnerThread())
                throw new IllegalMonitorStateException();
            // 释放标识
			boolean free = false;
            if (c == 0) {
                free = true;
				// 已经释放，清空独占
                setExclusiveOwnerThread(null);
            }
			// 设置标识
            setState(c);
            return free;
        }
		// 判断资源是否被当前线程占有
        protected final boolean isHeldExclusively() {
            // While we must in general read state before owner,
            // we don't need to do so to check if current thread is owner
            return getExclusiveOwnerThread() == Thread.currentThread();
        }
		
		// 新生一个条件
        final ConditionObject newCondition() {
            return new ConditionObject();
        }

        // 返回资源的占用线程
        final Thread getOwner() {
            return getState() == 0 ? null : getExclusiveOwnerThread();
        }

		// 返回状态
        final int getHoldCount() {
            return isHeldExclusively() ? getState() : 0;
        }

        final boolean isLocked() {
            return getState() != 0;
        }

        /**
         * Reconstitutes the instance from a stream (that is, deserializes it).
         */
        private void readObject(java.io.ObjectInputStream s)
            throws java.io.IOException, ClassNotFoundException {
            s.defaultReadObject();
            setState(0); // reset to unlocked state
        }
    }

    
	
	
	/**
     * Sync object for non-fair locks
     */
	 //非公平锁，继承了内部类Sync
	 // 从lock方法的源码可以看出来，每一次都尝试获取锁，而并不会按照公平等待的原则进行等待，让等待时间最久的线程获得锁
    static final class NonfairSync extends Sync {
		// 版本号
        private static final long serialVersionUID = 7316153563782823691L;

       // 获得锁
        final void lock() {
            // 比较并设置状态成功，状态为0表示锁没有被占用
			// 先通过CAS尝试将状态从0修改为1。若直接修改成功，则直接将线程的OWNER修改为当前线程
			if (compareAndSetState(0, 1))
				// 把当前线程设置为独占锁
                setExclusiveOwnerThread(Thread.currentThread());
            else //锁已经被占用，或者set失败
				// 已独占模式获取对象，忽略中断
				// 这个方法是AQS中的方法
                acquire(1);
        }

        protected final boolean tryAcquire(int acquires) {
            return nonfairTryAcquire(acquires);
        }
    }

    //公平锁，继承了内部类Sync
	
    static final class FairSync extends Sync {
        private static final long serialVersionUID = -3000897897090466540L;
		//获取锁操作，调用了acquire（）方法，该方法是AQS的方法
        final void lock() {
            acquire(1);
        }

        // 尝试公平获取锁
        protected final boolean tryAcquire(int acquires) {
            // 获取当前线程
			final Thread current = Thread.currentThread();
            // 获取状态
			int c = getState();
            if (c == 0) { // 如果状态为0
				// 不存在已经等待更久的线程并且比较和设置状态成功
                if (!hasQueuedPredecessors() &&
                    compareAndSetState(0, acquires)) {
					// 设置当前线程独占
                    setExclusiveOwnerThread(current);
                    return true;
                }
            }
			// 状态不为0， 即资源已经被当前线程独占
            else if (current == getExclusiveOwnerThread()) {
                // 下一个状态
				int nextc = c + acquires;
				// 超过Int表示的范围
                if (nextc < 0)
                    throw new Error("Maximum lock count exceeded");
                // 设置状态
				setState(nextc);
                return true;
            }
            return false;
        }
    }

    // 默认为非公平锁
    public ReentrantLock() {
        sync = new NonfairSync();
    }

    /**
     * Creates an instance of {@code ReentrantLock} with the
     * given fairness policy.
     *
     * @param fair {@code true} if this lock should use a fair ordering policy
     */
    public ReentrantLock(boolean fair) {
        sync = fair ? new FairSync() : new NonfairSync();
    }

   
   //调用AQS的lock()方法
    public void lock() {
        sync.lock();
    }

    
    public void lockInterruptibly() throws InterruptedException {
        sync.acquireInterruptibly(1);
    }

   
    public boolean tryLock() {
        return sync.nonfairTryAcquire(1);
    }

 
    public boolean tryLock(long timeout, TimeUnit unit)
            throws InterruptedException {
        return sync.tryAcquireNanos(1, unit.toNanos(timeout));
    }

  
    public void unlock() {
        sync.release(1);
    }

   
    public Condition newCondition() {
        return sync.newCondition();
    }

    
    public int getHoldCount() {
        return sync.getHoldCount();
    }

   
    public boolean isHeldByCurrentThread() {
        return sync.isHeldExclusively();
    }

    /**
     * Queries if this lock is held by any thread. This method is
     * designed for use in monitoring of the system state,
     * not for synchronization control.
     *
     * @return {@code true} if any thread holds this lock and
     *         {@code false} otherwise
     */
    public boolean isLocked() {
        return sync.isLocked();
    }

    /**
     * Returns {@code true} if this lock has fairness set true.
     *
     * @return {@code true} if this lock has fairness set true
     */
    public final boolean isFair() {
        return sync instanceof FairSync;
    }

    protected Thread getOwner() {
        return sync.getOwner();
    }

    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    public final boolean hasQueuedThread(Thread thread) {
        return sync.isQueued(thread);
    }

    public final int getQueueLength() {
        return sync.getQueueLength();
    }

    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }

    
    public boolean hasWaiters(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.hasWaiters((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    public int getWaitQueueLength(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitQueueLength((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    protected Collection<Thread> getWaitingThreads(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitingThreads((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    public String toString() {
        Thread o = sync.getOwner();
        return super.toString() + ((o == null) ?
                                   "[Unlocked]" :
                                   "[Locked by thread " + o.getName() + "]");
    }
}
