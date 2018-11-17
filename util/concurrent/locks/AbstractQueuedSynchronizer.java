

package java.util.concurrent.locks;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import sun.misc.Unsafe;

/** 使用AQS来实现一个同步器需要覆盖实现如下几个方法，并且使用getState(),setState(),compareAndSetState()这几个方法来设置获取状态 
* 1. boolean tryAcquire(int arg)  独占式获取同步状态，使用CAS设置同步状态
* 2. boolean tryRelease(int arg)  独占式释放同步状态
* 3. int tryAcquireShared(int arg) 共享式的获取同步状态，返回大于等于0的值，表示获取成功，否则失败
* 4. boolean tryReleaseShared(int arg) 共享式的释放同步状态
* 5. boolean isHeldExclusively() 判断当前是否被当前线程锁独占
*
* 支持独占(排他)获取锁的同步器应该实现tryAcquire、 tryRelease、isHeldExclusively
* 支持共享获取的同步器应该实现tryAcquireShared、tryReleaseShared、isHeldExclusively
*
*
* AQS是由一个同步队列（FIFO双向队列）来管理同步状态的，如果线程获取同步状态失效
* AQS会将当前线程以及等待状态信息构成一个结点Node加入到同步队列中，同时阻塞当前线程
* 当同步状态释放时，会把首节点中的线程唤醒，使其再次尝试获取同步状态
*/

// 同步器是用来构建锁和其他同步组件的基础框架，它的实现主要依赖一个int成员变量来表示同步状态
// 以及通过一个FIFO队列构成等待队列。它的子类必须重写AQS的几个protected修饰的用来改变同步状态的方法，
// 其他方法主要是实现了排队和阻塞机制。
// 状态的更新使用getState, setState和compareAndSetState()方法

/**
* 独占模式和共享模式的最大区别在于独占模式只允许一个线程持有资源，而共享模式下，当调用doAcquireShared时
* 会看后续结点是否是共享模式，如果是，会通过unpack唤醒后续节点，被唤醒的节点是被堵塞在doAcquireShared的parkAndCheckInterrupt方法
* 因此唤醒之后，会再次调用setHeadAndPropagate，从而将等待共享所的线程都唤醒，也就是说会将唤醒传播下去
*
* 1. 加入同步队列并阻塞的结点，它的前驱结点只会是SIGNAL,表示前驱结点释放锁时，后继结点会被唤醒。
*    shouldParkAfterFailedAcquire方法保证了这点，如果前驱结点不是SIGNAL,它会把他修改成SIGNAL
*
* 2. 造成前驱节点是PROPAGATE的情况是前驱节点获得锁时，会唤醒一次后继节点，但这时候后继节点还没有加入到同步队列，
*    所以暂时把节点状态设置为PROPAGATE,当后继节点加入同步队列后，
*    会把PROPAGATE设置为SIGNAL,这样前驱节点释放锁时会再次doReleaseShared，
*    这时候它的状态已经是SIGNAL了，就可以唤醒后续节点了。
*/

// 举个栗子： 我们去医院看医生，挂号在某个特定教授。如果当前该教授没有任何病人在看病（相当于tryAcquire）
// 那么我们不需要排队就可以进去看病。否则的话，我们要在门口排队（相当于addWaiter），等待当前看病的病人出来
// 出来一个（相当于release）。则正在队列头部的病人可以进去看病了（大概相当于acquireQueued）
public abstract class AbstractQueuedSynchronizer
    extends AbstractOwnableSynchronizer
    implements java.io.Serializable {

    private static final long serialVersionUID = 7373984972572414691L;

    protected AbstractQueuedSynchronizer() { }

   /**
   * 每个Node结点都是一个自旋锁：在阻塞时不断循环读取状态变量，当前驱节点释放同步对象使用权后，
   * 跳出循环，执行同步代码。
   */
   
   // AQS会将等待线程封装成Node：双向、带头结点的
   // 可以看到Node中记录了等待的线程对象、结点状态和前后结点，并且通过nextWaiter判断是独占的还是共享模式
   // 独占模式：每次只能有一个线程持有资源
   // 共享模式：允许多个线程同时持有资源
    static final class Node {
        //共享锁是空对象
        static final Node SHARED = new Node();
        //独占锁是null
        static final Node EXCLUSIVE = null;

        // 表示结点的线程是已被取消的。当前结点由于超时或者被中断而被取消。
		// 一旦结点被取消后，那么它的状态值不会再被改变，且当前结点的线程不会再次被阻塞
        static final int CANCELLED =  1;
		
		// 表示当前节点的后继结点的线程需要被唤醒。当前节点的后继结点已经（或即将）被阻塞（通过LockSupport.park()），
		// 所以当前节点释放或者被取消时候，一定要unpark它的后继结点。为了避免竞争，获取方法一定要首先设置node为signal
		// 然后再次重新调用获取方法，如果失败，则阻塞
		
		// 解释二：表示下一个节点是通过park阻塞的，需要通过unpark唤醒
		static final int SIGNAL    = -1;
        
		// 表示线程正在等待某个条件。表示当前结点正在条件队列（AQS下的ConditionObject里也维护了一个队列）中，在从conditionObject
		// 队列转移到同步队列前，它不会在同步队列中被使用。当成功转移后，该结点的状态值将由CONDITION变为0
        static final int CONDITION = -2;
        
		//新节点会处于这种状态
        static final int PROPAGATE = -3;

       // 用来表示当前结点的状态，其取值范围有4种：1，-1，-2，-3
        volatile int waitStatus;
	
		// 用于连接当前结点的前驱结点，当前结点依赖前驱结点来检测waitStatus，前驱结点是在当前结点入队时候被设置的。
		// 为了提高GC效率，在当前结点出兑时候会把前驱结点设置为null，而且，在取消前驱结点的时候，则会while循环知道找到一个非取消的结点，
		// 由于头结点永远不会是取消状态，所以我们一定可以找到非取消状态的前置节点
        volatile Node prev;
		

		// 用于连接当前结点的后继结点，在当前结点释放时候会唤醒后继结点。在一个当前结点入队的时候
		// 会先设置当前结点的prev，而不会立即设置前置结点的next。而是用CAS替换了tail之后才设置前置结点的next
        volatile Node next;

        // 加入同步队列的线程引用
        volatile Thread thread;
		
		// 对于condition表示下一个等待条件变量的结点：其他情况下用于区分共享模式和独占模式
        Node nextWaiter;
		
		
        /**
         * Returns true if node is waiting in shared mode.
         */
        final boolean isShared() {
            return nextWaiter == SHARED; //判断是否是共享模式
        }

		// 获取当前结点的前驱结点，如果为null，则抛出异常
        final Node predecessor() throws NullPointerException {
            Node p = prev;
            if (p == null)
                throw new NullPointerException();
            else
                return p;
        }

        Node() {    // Used to establish initial head or SHARED marker
        }

        Node(Thread thread, Node mode) {     // addWaiter方法使用
            this.nextWaiter = mode;
            this.thread = thread;
        }

        Node(Thread thread, int waitStatus) { // Condition方法使用
            this.waitStatus = waitStatus;
            this.thread = thread;
        }
    }
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	// 等待队列头部，延迟初始化，直到调用enq才真正初始化
    private transient volatile Node head;

	// 等待队列尾部，延迟初始化，直到调用enq才真正初始化
    private transient volatile Node tail;

    /**
     * AQS定义了一个volatile整数状态信息，我们可以通过getState(),setState（int newState）
	 * compareAndSetState(int expect, int update)等protected方法进行操作这一状态信息。
	 * 例如：ReentrantLock中用它来表示所有线程已经重复获取该锁的次数
	 * 		Semaphore用它来表示剩余的许可数量； 
	 *		FutureTask用它来表示任务的状态(未开始，正在运行，已结束，已取消等)
     */
    private volatile int state;

    /**
     * Returns the current value of synchronization state.
     * This operation has memory semantics of a {@code volatile} read.
     * @return current state value
     */
    protected final int getState() {
        return state;
    }

    /**
     * Sets the value of synchronization state.
     * This operation has memory semantics of a {@code volatile} write.
     * @param newState the new state value
     */
    protected final void setState(int newState) {
        state = newState;
    }

  
    protected final boolean compareAndSetState(int expect, int update) {
        // See below for intrinsics setup to support this
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }

    // 自旋锁超时阈值

    static final long spinForTimeoutThreshold = 1000L;

    /**
	* 1. 死循环，获取到末端结点，如果是null，则使用CAS创建一个头结点（头结点此时也是null），并将头结点赋值末端结点
	* 2. 由于刚刚CAS成功，走else逻辑，将末端结点赋值给新节点的prev属性，使用CAS设置新的末端结点为刚刚创建的node对象
	*    然后返回node对象
	*/
	
	//该方法主要是初始化头结点和末端结点，并将新的结点追加到末端结点并更新末端结点
	
	private Node enq(final Node node) {
        for (;;) {
			// 记录尾结点
            Node t = tail;
			// 由于采用懒加载，当队列为空时，需要进行初始化
            if (t == null) { 
				// 通过CAS设置head和tail结点
                if (compareAndSetHead(new Node()))
                    tail = head;
            } else {
				// 将node的前结点设置为原tail结点
                node.prev = t;
				// 使用CAS更新tail结点，更新成功则将原tail结点的后继设置为node，返回原tail结点，入队成功
                if (compareAndSetTail(t, node)) {
                    t.next = node;
                    return t;
                }
            }
        }
    }

    // 将当前线程放入到队列结点。参数Node有两个值：Node.EXCLUSIVE是独占锁，Node.SHARED是分享锁
    // 该方法的主要作用就是根据当前线程创建一个node对象，并追加到队列的末端
	/**
	* 1. 创建一个当前线程的Node对象
	* 2. 获取到末端结点，如果末端结点不为null，则将末端结点设置为刚刚创建的结点的prev属性
	*    2.1 通过CAS设置末端结点为新的结点，如果成功，将刚刚创建的结点设置为末端结点的next结点
	* 3. 如果tail末端结点是null，则调用enq方法。创建一个末端结点。然后，将刚刚创建的末端结点设置为新节点的prev属性
	*	 (此时的末端结点就是head头结点)。最后返回刚刚创建的node结点
	*/
	
	// 该方法可以得到如下结论：
	// 1. head结点实际上是空节点
	// 2. head结点是通过new Node()创建，因此waitStatus = 0
	// 3. 新入队列的结点是通过Node(Thread thread. Node node)创建， waitStatus = 0
	private Node addWaiter(Node mode) {
		// 根据传入的模式mode（独占Or共享）创建node对象
        Node node = new Node(Thread.currentThread(), mode);
        // 获取到tail结点（也就是接下来，当前结点的前驱结点）
        Node pred = tail;
		
		// 如果pred不为空，说明有线程在等待
		// 尝试使用CAS入列，如果入列失败，则调用enq采用咨询的方式入队列
		// 该逻辑在无竞争的情况下才会成功，快速入队列
        if (pred != null) {
			// 所谓入列，就是将结点设置为新的tail结点
			//注意：有可能设置node的前节点成功，但是CAS更新失败；
			//这种情况下，由于无法从head或tail找到节点，问题不大；
			//但是对于isOnSyncQueue这种方法，则会造成影响，需要特殊处理
            node.prev = pred;
			//通过CAS更新tail结点，如果成功，则返回
			// private final boolean compareAndSetTail(Node expect, Node update)
			// compareAndSetTail(pred, node) : 表示：比较pred的值和tail的值，如果相等，则将node的值赋值给tail
			// 那么此时tail就指向了node, nodeJ节点变成了新的tail，而pred没有变，还是之前tail指向的结点
            if (compareAndSetTail(pred, node)) {
				// 将原tail结点的后结点设置为新的tail结点
				// 由于CAS和设置next不是原子操作，因此可能出现更新tail结点成功，但未执行pred.next = node;
				// 但是由于当前已经设置了node的prev属性，因此可以从尾部开始遍历
                pred.next = node;
                return node;
            }
        }
		// 上述不成功，存在多线程竞争，则自旋
        enq(node);
        return node;
    }
	
    
    private void setHead(Node node) {
        head = node;
        node.thread = null;
        node.prev = null;
    }

    
    private void unparkSuccessor(Node node) {
        
        int ws = node.waitStatus;
        if (ws < 0)
            compareAndSetWaitStatus(node, ws, 0);

        // 如果结点为空或者被取消了，则从队列尾部开始查找，找到离node最近的非null且状态正常的结点
		Node s = node.next;
        if (s == null || s.waitStatus > 0) {
            s = null;
            for (Node t = tail; t != null && t != node; t = t.prev)
                if (t.waitStatus <= 0)
                    s = t;
        }
		// 取出找到结点的线程对象，通过unpark，颁发许可；
        if (s != null)
            LockSupport.unpark(s.thread);
    }

    /**
     * Release action for shared mode -- signals successor and ensures
     * propagation. (Note: For exclusive mode, release just amounts
     * to calling unparkSuccessor of head if it needs signal.)
     */
	 
	 //计数器-1后，调用该方法
    private void doReleaseShared() {
        
		 //开启自旋
        for (;;) {
            Node h = head;
            if (h != null && h != tail) {
                int ws = h.waitStatus;
                if (ws == Node.SIGNAL) {
                    if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0))
						//使用continue，是为了确保Status的状态一定会修改成功
                        continue;            // loop to recheck cases
					//唤醒挂起的线程，也就是doAcquireSharedInterruptibly（）当中的自旋执行一次，可能再次挂起或者结束
                    unparkSuccessor(h);
                }
				//唤醒线程后，head值改变的话，更新Status为PROPAGATE
                else if (ws == 0 &&
                         !compareAndSetWaitStatus(h, 0, Node.PROPAGATE))
					//确保Status一定会修改成功
                    continue;                // loop on failed CAS
            }
			 //判读head是否更换（唤醒挂起的线程，有可能将head更新，也可能不更新）
            if (h == head)                   // loop if head changed
			//在这里，可以粗略的理解为：如果唤醒线程后，该线程仍然挂起，结束该方法的自旋
                break;
				 //如果head改变，继续自旋，这样，就会调用compareAndSetWaitStatus(h, 0, Node.PROPAGATE)
    
        }
    }

    /**
     * Sets head of queue, and checks if successor may be waiting
     * in shared mode, if so propagating if either propagate > 0 or
     * PROPAGATE status was set.
     *
     * @param node the node
     * @param propagate the return value from a tryAcquireShared
     */
    private void setHeadAndPropagate(Node node, int propagate) {
        Node h = head; // Record old head for check below
        setHead(node);
        /*
         * Try to signal next queued node if:
         *   Propagation was indicated by caller,
         *     or was recorded (as h.waitStatus either before
         *     or after setHead) by a previous operation
         *     (note: this uses sign-check of waitStatus because
         *      PROPAGATE status may transition to SIGNAL.)
         * and
         *   The next node is waiting in shared mode,
         *     or we don't know, because it appears null
         *
         * The conservatism in both of these checks may cause
         * unnecessary wake-ups, but only when there are multiple
         * racing acquires/releases, so most need signals now or soon
         * anyway.
         */
        if (propagate > 0 || h == null || h.waitStatus < 0 ||
            (h = head) == null || h.waitStatus < 0) {
            Node s = node.next;
            if (s == null || s.isShared())
                doReleaseShared();
        }
    }

    // Utilities for various versions of acquire

    /**
     * Cancels an ongoing attempt to acquire.
     *
     * @param node the node
     */
    private void cancelAcquire(Node node) {
        if (node == null)
            return;

        node.thread = null;

        // 获取node的前驱结点
        Node pred = node.prev;
		// 如果发现前驱结点的状态为CANCELLED,则继续向前找，直到找到状态正常的节点
        while (pred.waitStatus > 0)
            node.prev = pred = pred.prev;

        
        Node predNext = pred.next;

        // 结点状态设置为CANCELLED
		node.waitStatus = Node.CANCELLED;

        // 如果node为tail结点，则将pred更新为tail结点.
        if (node == tail && compareAndSetTail(node, pred)) {
			// 由于pred为新的尾结点，因此将其next设置为null
            compareAndSetNext(pred, predNext, null);
        } else {//如果node不是尾结点
            
			int ws;
            // 当满足下面三个条件，将pred的next指向node的下一个节点
			// 1. pred不是head结点，如果pred为头结点，而node又被cancel，则node.next为等待队列中的第一个节点，需要unpark唤醒
			// 2. pred结点状态位SIGNAL或能更新为SIGNAL
			// 3. pred的thread变量不能为null
			if (pred != head &&
                ((ws = pred.waitStatus) == Node.SIGNAL ||
                 (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&
                pred.thread != null) {
                Node next = node.next;
				// 更新pred的next，指向node的下一个节点
                if (next != null && next.waitStatus <= 0)
                    compareAndSetNext(pred, predNext, next);
            } else {
				// 如果pred为头结点，则唤醒node的后一个结点
                unparkSuccessor(node);
            }

            node.next = node; // help GC
        }
    }

    /**
     * 1. 获取上一个结点的等待状态，如果状态是SIGNAL = - 1, 就直接返回true，表示可以挂起并休息
	 * 2. 如果waitStatus大于0，则循环检查prev结点的prev的waitState，直到遇到一个状态不大于0
	 * 		该字段有4个状态，分别是CANCELLED = 1, SIGNAL = -1.CONDITION = -2， PROPAGATE = -3,
	 *		即如果大于0，就是取消状态。那么网上找到那个不大于0的结点后怎么办？将当前结点指向那个结点的next结点
	 *		即那些大于0状态的结点都是失效结点，随时被GC回收
	 * 3. 如果不大于0也不是-1，则将上一个节点的状态设置为有效，也就是-1，最后返回false。
	 *		注意，在acquireQueued方法中，返回false后会继续循环，此时pred结点已经是-1了，因此最终会返回true	
     */
	 
	 // 这个方法是信号控制的核心。在获取同步状态失败，生成Node并加入队列中后，用于检查和更新结点的状态。
	 // 返回true表示当前结点应该被阻塞
	 
    private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
        // 如果acquireQueued第一次调用该方法，ws  = 0
		int ws = pred.waitStatus;
        // 已经设置了状态，由于SIGNAL表示要通过unpark唤醒后继结点，因此当获取失败的时候，是要调用park阻塞，返回true
		if (ws == Node.SIGNAL)
            return true;
		// 如果前一个结点已经取消，则往前找，知道找到一个状态正常的结点，其实就是从队列删除取消状态的结点
        if (ws > 0) {
            /*
             * 当前驱结点状态大于0（只有一个取值：1），表示前驱结点已经取消
			 * 此时应该丢弃前驱结点，而继续寻找前驱节点的前驱节点
			 * 这里使用while循环查找前驱节点，并将当前结点的prev属性设置为找到的新节点
			 * 并将新的前驱节点的后继结点设置为当前结点
             */
            do {
                node.prev = pred = pred.prev;
            } while (pred.waitStatus > 0);
            pred.next = node; // 更新next指针，去掉中间取消状态的结点
        } else {
            /*
             * 排除以上SIGNAL(-1)和 >0 两种情况
			 * 现在是前驱节点的waitStatus的为0或者PROPAGATE(-3)的情况（不考虑CONDITION情况）
			 * 这时候表明前驱节点需要重新设置waitStatus
			 * 这样在下一轮循环中，就可以判断前驱节点的SIGNAL而阻塞park当前节点，以便于等待前驱节点的unpark（比如：shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt()）
             */
			 // 更新pred结点的waitStatus为SIGNAL
            compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
        }
		// 返回false，表示不需要调用park
        return false;
    }

    /**
     * Convenience method to interrupt current thread.
     */
	 // 将当前线程设置中断状态位
    static void selfInterrupt() {
        Thread.currentThread().interrupt();
    }

    /**
     * Convenience method to park and then check if interrupted
     *
     * @return {@code true} if interrupted
     */
	// 将当前线程挂起，等到有别的线程唤醒（通常是head结点中的线程），然后返回当前线程是否是被中断了
	// 该方法会清除中断状态
	
    private final boolean parkAndCheckInterrupt() {
        // 将当前线程的parkBlocker变量指向this，调用unsafe.park堵塞当前线程
		// 简单来说park是申请许可，如果存在许可，马上返回，否则一直等待获得许可;unpark是将许可数量设为1，会唤醒park返回;
		// LockSupport提供了unpark(Thread thread)方法，可以为指定线程颁发许可
		LockSupport.park(this);
		// 该方法会清除线程的中断状态
        return Thread.interrupted();
    }

    
    /**
     * 1. 死循环。先获取node对象prev结点，如果该节点和head相同，说明他是第二个结点。那么此时就可以尝试获取锁了
	 * 		1.1 如果获取锁成功，就设置当前结点的head结点（同时设置当前node的线程为null，prev为null）。
	 *			并设置他的prev结点的next结点为null（帮助GC回收）。最后，返回等待过程中是否中断地布尔值
	 * 2. 如果上面的两个条件不成立，则调用shouldParkAfterFailedAcquire方法和parkAndCheckInterrupt方法。
	 * 		这两个方法的目的就是讲当前线程挂起。然后等待被唤醒或者被中断。
	 * 3. 如果挂起后被当前线程唤醒，则再度循环，判断是该结点的prev节点是否是head,一般来讲，当你被唤醒，说明你被准许去拿锁了。
	 * 		也就是head结点完成了任务释放了锁，然后重复步骤1，最后返回
     */
    final boolean acquireQueued(final Node node, int arg) {
        boolean failed = true;
        try {
            boolean interrupted = false; // 未发生中断
			// 仍然通过自旋，根据前面的逻辑，此处传入的为新如队列的结点
            for (;;) {
				// 获取前驱结点，即prev指向的结点
                final Node p = node.predecessor();
				// 如果node的前驱结点是head结点，而且head结点是空节点，说明node是等待队列中的第一个结点
				// 问：为什么是前驱节点而不是当前结点？因为我们队列在初始化的时候生成了虚头结点
                if (p == head && tryAcquire(arg)) {
					// 获取资源成功，将node设置为头结点，setHead清空结点属性thread和prev
                    setHead(node);
                    p.next = null; // 将原头结点的next设置为null，帮助gc
                    failed = false;
                    return interrupted; // 返回是否发生中断
                }
				
				// 如果acquire失败，是否要park，如果是则调用LockSupport.park
				// 判断当前结点的线程是否应该被挂起，如果应该被挂起则挂起。等待release唤醒。
				// 问：为什么要挂起当前线程？因为如果不挂起的话，线程会一直抢占CPU
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt())
                    interrupted = true; // 发生中断
            }
        } finally {
            if (failed) // 只有循环中出现异常，才会进入该逻辑
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in exclusive interruptible mode.
     * @param arg the acquire argument
     */
    private void doAcquireInterruptibly(int arg)
        throws InterruptedException {
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return;
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in exclusive timed mode.
     *
     * @param arg the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L)
            return false;
        final long deadline = System.nanoTime() + nanosTimeout;
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return true;
                }
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L)
                    return false;
                if (shouldParkAfterFailedAcquire(p, node) &&
                    nanosTimeout > spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if (Thread.interrupted())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    
    private void doAcquireShared(int arg) {
        // 创建共享结点并加入到等待队列
		final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (;;) {
                final Node p = node.predecessor();
				// 如果是第一个等待结点，则调用tryAcquireShared
				// 负数表示失败；0表示成功但无法传播；1表示成功且可传播
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
						// 设置head结点，检查下一个等待结点是否是共享模式，如果是，向下传播
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        if (interrupted)
                            selfInterrupt(); // 判断中断状态
                        failed = false;
                        return;
                    }
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt())
                    interrupted = true;
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in shared interruptible mode.
     * @param arg the acquire argument
     */
	 //计数器不为0时，调用该方法
    private void doAcquireSharedInterruptibly(int arg)
        throws InterruptedException {
		//向队列中添加一个结点（如果当前队列没有head，先创建head，再添加node）
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
			//开启自旋，如果计数器为0，会结束自旋
            for (;;) {
				//获取当前node的前一个node
                final Node p = node.predecessor();
				//判断当前node是不是第一个node
                if (p == head) {
					//如果是第一个node，查看计数器是否为0
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
						//计数器为0，说明所有的锁都释放了
						//将当前的node设置为head,也就是将队列中第一个node设置为head，同时也会唤醒挂起的线程
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
						//该方法执行完毕，调用await（）的线程可以继续执行
                        return;
                    }
                }
				//调用await（）的线程挂起
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in shared timed mode.
     *
     * @param arg the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L)
            return false;
        final long deadline = System.nanoTime() + nanosTimeout;
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return true;
                    }
                }
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L)
                    return false;
                if (shouldParkAfterFailedAcquire(p, node) &&
                    nanosTimeout > spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if (Thread.interrupted())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    // Main exported methods

    /**
     * Attempts to acquire in exclusive mode. This method should query
     * if the state of the object permits it to be acquired in the
     * exclusive mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread. This can be used
     * to implement method {@link Lock#tryLock()}.
     *
     * <p>The default
     * implementation throws {@link UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return {@code true} if successful. Upon success, this object has
     *         been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
	 
	 //该方法默认抛出异常，由其子类实现
    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in exclusive
     * mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this object is now in a fully released
     *         state, so that any waiting threads may attempt to acquire;
     *         and {@code false} otherwise.
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to acquire in shared mode. This method should query if
     * the state of the object permits it to be acquired in the shared
     * mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread.
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return a negative value on failure; zero if acquisition in shared
     *         mode succeeded but no subsequent shared-mode acquire can
     *         succeed; and a positive value if acquisition in shared
     *         mode succeeded and subsequent shared-mode acquires might
     *         also succeed, in which case a subsequent waiting thread
     *         must check availability. (Support for three different
     *         return values enables this method to be used in contexts
     *         where acquires only sometimes act exclusively.)  Upon
     *         success, this object has been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in shared mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this release of shared mode may permit a
     *         waiting acquire (shared or exclusive) to succeed; and
     *         {@code false} otherwise
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns {@code true} if synchronization is held exclusively with
     * respect to the current (calling) thread.  This method is invoked
     * upon each call to a non-waiting {@link ConditionObject} method.
     * (Waiting methods instead invoke {@link #release}.)
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}. This method is invoked
     * internally only within {@link ConditionObject} methods, so need
     * not be defined if conditions are not used.
     *
     * @return {@code true} if synchronization is held exclusively;
     *         {@code false} otherwise
     * @throws UnsupportedOperationException if conditions are not supported
     */
    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

   
	 //以独占模式获取对象，如果被中断则中止。通过先检查中断状态，然后至少调用一次tryAcquire()来实现此方法
	 //并在成功时返回。否则在成功之前，或者线程被中断之前，一直调用tryAcquire()将线程加入队列。线程可能重复被阻塞或不被阻塞
	 //可以使用此方法来实现Lock.lockInterruptibly
	 
	 /**
	 * 该方法会以独占式的方式获取锁，如果获取不到，就会被加入等待队列等待被唤醒
	 * 如果获取成功，则直接返回
	 */
	 
	 // 解释二
	 // 步骤1：调用tryAcquire(arg)，如果返回false，表示获取资源失败，需要进行排队获取
	 // 步骤2：调用addWaiter，创建独占模式Node,并加入到等待队列尾部
	 // 步骤3：调用acquireQueued方法，按照线程加入队列的顺序获取资源
	 // 步骤4：如果acquireQueued返回true，表示发生中断，因此通过selfInterrupt中断当前线程
	 
    public final void acquire(int arg) {
		// tryAcquire()方法：尝试获取锁。该方法是需要被重写，父类的该方法默认是抛出异常，
        // addWaiter(Node.EXCLUSIVE)：方法返回刚刚创建的node对象，然后调用acquireQueued()方法
		// 该方法如果获取锁失败并被唤醒，且被中断了，那么执行selfInterrupt()方法
		if (!tryAcquire(arg) &&
            acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
            selfInterrupt();
    }

    // 与acquire方法相同，但在同步队列中进行等待的时候可以检测中断
    public final void acquireInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        if (!tryAcquire(arg))
            doAcquireInterruptibly(arg);
    }

    // 在acquireInterruptibly方法的基础上增加了超时等待的功能，在超时时间内没有获得同步状态返回false
    public final boolean tryAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        return tryAcquire(arg) ||
            doAcquireNanos(arg, nanosTimeout);
    }

    // 释放同步状态，该方法会唤醒在同步队列中的下一个节点
    public final boolean release(int arg) {
        if (tryRelease(arg)) {
            Node h = head;
            if (h != null && h.waitStatus != 0)
                unparkSuccessor(h);
            return true;
        }
        return false;
    }

    // acquireShared用于共享模式下获取资源，该方法会忽略中断
    public final void acquireShared(int arg) {
        if (tryAcquireShared(arg) < 0)
            doAcquireShared(arg);
    }

    /**
     * Acquires in shared mode, aborting if interrupted.  Implemented
     * by first checking interrupt status, then invoking at least once
     * {@link #tryAcquireShared}, returning on success.  Otherwise the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted.
     * @param arg the acquire argument.
     * This value is conveyed to {@link #tryAcquireShared} but is
     * otherwise uninterpreted and can represent anything
     * you like.
     * @throws InterruptedException if the current thread is interrupted
     */
	 
	 //await()中实际上是调用该方法
    public final void acquireSharedInterruptibly(int arg)
            throws InterruptedException {
		//处理异常
        if (Thread.interrupted())
            throw new InterruptedException();
        //尝试获取锁，在这里，是调用CountDownLatch当中AbstractQueuedSynchronizer具体类中重写的方法
		if (tryAcquireShared(arg) < 0)
			//计数器大小不为0.执行该方法
            doAcquireSharedInterruptibly(arg);
    }

    /**
     * Attempts to acquire in shared mode, aborting if interrupted, and
     * failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquireShared}, returning on success.  Otherwise, the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted or the timeout elapses.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquireShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        return tryAcquireShared(arg) >= 0 ||
            doAcquireSharedNanos(arg, nanosTimeout);
    }

    // 该方法会唤醒第一个等待结点，根据前面的acquireShared的逻辑，被唤醒的线程会通过setHeadAndPropagate继续唤醒后续等待的线程
	 //countDown实际上调用的是该方法
    public final boolean releaseShared(int arg) {
		//尝试释放锁，在这里，是调用CountDownLatch当中AbstractQueuedSynchronizer具体类中重写的方法
		//计数器-1，返回true,计数器大小没变，返回false
        if (tryReleaseShared(arg)) {
			//计数器大小-1，进入判断内部
			doReleaseShared();
            return true;
        }
        return false;
    }

    // Queue inspection methods

    /**
     * Queries whether any threads are waiting to acquire. Note that
     * because cancellations due to interrupts and timeouts may occur
     * at any time, a {@code true} return does not guarantee that any
     * other thread will ever acquire.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there may be other threads waiting to acquire
     */
    public final boolean hasQueuedThreads() {
        return head != tail;
    }

    /**
     * Queries whether any threads have ever contended to acquire this
     * synchronizer; that is if an acquire method has ever blocked.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there has ever been contention
     */
    public final boolean hasContended() {
        return head != null;
    }

    /**
     * Returns the first (longest-waiting) thread in the queue, or
     * {@code null} if no threads are currently queued.
     *
     * <p>In this implementation, this operation normally returns in
     * constant time, but may iterate upon contention if other threads are
     * concurrently modifying the queue.
     *
     * @return the first (longest-waiting) thread in the queue, or
     *         {@code null} if no threads are currently queued
     */
    public final Thread getFirstQueuedThread() {
        // handle only fast path, else relay
        return (head == tail) ? null : fullGetFirstQueuedThread();
    }

    /**
     * Version of getFirstQueuedThread called when fastpath fails
     */
    private Thread fullGetFirstQueuedThread() {
        /*
         * The first node is normally head.next. Try to get its
         * thread field, ensuring consistent reads: If thread
         * field is nulled out or s.prev is no longer head, then
         * some other thread(s) concurrently performed setHead in
         * between some of our reads. We try this twice before
         * resorting to traversal.
         */
        Node h, s;
        Thread st;
        if (((h = head) != null && (s = h.next) != null &&
             s.prev == head && (st = s.thread) != null) ||
            ((h = head) != null && (s = h.next) != null &&
             s.prev == head && (st = s.thread) != null))
            return st;

        /*
         * Head's next field might not have been set yet, or may have
         * been unset after setHead. So we must check to see if tail
         * is actually first node. If not, we continue on, safely
         * traversing from tail back to head to find first,
         * guaranteeing termination.
         */

        Node t = tail;
        Thread firstThread = null;
        while (t != null && t != head) {
            Thread tt = t.thread;
            if (tt != null)
                firstThread = tt;
            t = t.prev;
        }
        return firstThread;
    }

    /**
     * Returns true if the given thread is currently queued.
     *
     * <p>This implementation traverses the queue to determine
     * presence of the given thread.
     *
     * @param thread the thread
     * @return {@code true} if the given thread is on the queue
     * @throws NullPointerException if the thread is null
     */
    public final boolean isQueued(Thread thread) {
        if (thread == null)
            throw new NullPointerException();
        for (Node p = tail; p != null; p = p.prev)
            if (p.thread == thread)
                return true;
        return false;
    }

    /**
     * Returns {@code true} if the apparent first queued thread, if one
     * exists, is waiting in exclusive mode.  If this method returns
     * {@code true}, and the current thread is attempting to acquire in
     * shared mode (that is, this method is invoked from {@link
     * #tryAcquireShared}) then it is guaranteed that the current thread
     * is not the first queued thread.  Used only as a heuristic in
     * ReentrantReadWriteLock.
     */
    final boolean apparentlyFirstQueuedIsExclusive() {
        Node h, s;
        return (h = head) != null &&
            (s = h.next)  != null &&
            !s.isShared()         &&
            s.thread != null;
    }

    /**
     * Queries whether any threads have been waiting to acquire longer
     * than the current thread.
     *
     * <p>An invocation of this method is equivalent to (but may be
     * more efficient than):
     *  <pre> {@code
     * getFirstQueuedThread() != Thread.currentThread() &&
     * hasQueuedThreads()}</pre>
     *
     * <p>Note that because cancellations due to interrupts and
     * timeouts may occur at any time, a {@code true} return does not
     * guarantee that some other thread will acquire before the current
     * thread.  Likewise, it is possible for another thread to win a
     * race to enqueue after this method has returned {@code false},
     * due to the queue being empty.
     *
     * <p>This method is designed to be used by a fair synchronizer to
     * avoid <a href="AbstractQueuedSynchronizer#barging">barging</a>.
     * Such a synchronizer's {@link #tryAcquire} method should return
     * {@code false}, and its {@link #tryAcquireShared} method should
     * return a negative value, if this method returns {@code true}
     * (unless this is a reentrant acquire).  For example, the {@code
     * tryAcquire} method for a fair, reentrant, exclusive mode
     * synchronizer might look like this:
     *
     *  <pre> {@code
     * protected boolean tryAcquire(int arg) {
     *   if (isHeldExclusively()) {
     *     // A reentrant acquire; increment hold count
     *     return true;
     *   } else if (hasQueuedPredecessors()) {
     *     return false;
     *   } else {
     *     // try to acquire normally
     *   }
     * }}</pre>
     *
     * @return {@code true} if there is a queued thread preceding the
     *         current thread, and {@code false} if the current thread
     *         is at the head of the queue or the queue is empty
     * @since 1.7
     */
    public final boolean hasQueuedPredecessors() {
        // The correctness of this depends on head being initialized
        // before tail and on head.next being accurate if the current
        // thread is first in queue.
        Node t = tail; // Read fields in reverse initialization order
        Node h = head;
        Node s;
        return h != t &&
            ((s = h.next) == null || s.thread != Thread.currentThread());
    }


    // Instrumentation and monitoring methods

    /**
     * Returns an estimate of the number of threads waiting to
     * acquire.  The value is only an estimate because the number of
     * threads may change dynamically while this method traverses
     * internal data structures.  This method is designed for use in
     * monitoring system state, not for synchronization
     * control.
     *
     * @return the estimated number of threads waiting to acquire
     */
    public final int getQueueLength() {
        int n = 0;
        for (Node p = tail; p != null; p = p.prev) {
            if (p.thread != null)
                ++n;
        }
        return n;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate.  The elements of the
     * returned collection are in no particular order.  This method is
     * designed to facilitate construction of subclasses that provide
     * more extensive monitoring facilities.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            Thread t = p.thread;
            if (t != null)
                list.add(t);
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in exclusive mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to an exclusive acquire.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getExclusiveQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (!p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in shared mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to a shared acquire.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getSharedQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a string identifying this synchronizer, as well as its state.
     * The state, in brackets, includes the String {@code "State ="}
     * followed by the current value of {@link #getState}, and either
     * {@code "nonempty"} or {@code "empty"} depending on whether the
     * queue is empty.
     *
     * @return a string identifying this synchronizer, as well as its state
     */
    public String toString() {
        int s = getState();
        String q  = hasQueuedThreads() ? "non" : "";
        return super.toString() +
            "[State = " + s + ", " + q + "empty queue]";
    }


    // Internal support methods for Conditions

    /**
     * Returns true if a node, always one that was initially placed on
     * a condition queue, is now waiting to reacquire on sync queue.
     * @param node the node
     * @return true if is reacquiring
     */
    final boolean isOnSyncQueue(Node node) {
        if (node.waitStatus == Node.CONDITION || node.prev == null)
            return false;
        if (node.next != null) // If has successor, it must be on queue
            return true;
        /*
         * node.prev can be non-null, but not yet on queue because
         * the CAS to place it on queue can fail. So we have to
         * traverse from tail to make sure it actually made it.  It
         * will always be near the tail in calls to this method, and
         * unless the CAS failed (which is unlikely), it will be
         * there, so we hardly ever traverse much.
         */
        return findNodeFromTail(node);
    }

    /**
     * Returns true if node is on sync queue by searching backwards from tail.
     * Called only when needed by isOnSyncQueue.
     * @return true if present
     */
    private boolean findNodeFromTail(Node node) {
        Node t = tail;
        for (;;) {
            if (t == node)
                return true;
            if (t == null)
                return false;
            t = t.prev;
        }
    }

    /**
     * Transfers a node from a condition queue onto sync queue.
     * Returns true if successful.
     * @param node the node
     * @return true if successfully transferred (else the node was
     * cancelled before signal)
     */
    final boolean transferForSignal(Node node) {
        /*
         * If cannot change waitStatus, the node has been cancelled.
         */
        if (!compareAndSetWaitStatus(node, Node.CONDITION, 0))
            return false;

        /*
         * Splice onto queue and try to set waitStatus of predecessor to
         * indicate that thread is (probably) waiting. If cancelled or
         * attempt to set waitStatus fails, wake up to resync (in which
         * case the waitStatus can be transiently and harmlessly wrong).
         */
        Node p = enq(node);
        int ws = p.waitStatus;
        if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL))
            LockSupport.unpark(node.thread);
        return true;
    }

    /**
     * Transfers node, if necessary, to sync queue after a cancelled wait.
     * Returns true if thread was cancelled before being signalled.
     *
     * @param node the node
     * @return true if cancelled before the node was signalled
     */
    final boolean transferAfterCancelledWait(Node node) {
        if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
            enq(node);
            return true;
        }
        /*
         * If we lost out to a signal(), then we can't proceed
         * until it finishes its enq().  Cancelling during an
         * incomplete transfer is both rare and transient, so just
         * spin.
         */
        while (!isOnSyncQueue(node))
            Thread.yield();
        return false;
    }

    /**
     * Invokes release with current state value; returns saved state.
     * Cancels node and throws exception on failure.
     * @param node the condition node for this wait
     * @return previous sync state
     */
    final int fullyRelease(Node node) {
        boolean failed = true;
        try {
            int savedState = getState();
            if (release(savedState)) {
                failed = false;
                return savedState;
            } else {
                throw new IllegalMonitorStateException();
            }
        } finally {
            if (failed)
                node.waitStatus = Node.CANCELLED;
        }
    }

    // Instrumentation methods for conditions

    /**
     * Queries whether the given ConditionObject
     * uses this synchronizer as its lock.
     *
     * @param condition the condition
     * @return {@code true} if owned
     * @throws NullPointerException if the condition is null
     */
    public final boolean owns(ConditionObject condition) {
        return condition.isOwnedBy(this);
    }

    /**
     * Queries whether any threads are waiting on the given condition
     * associated with this synchronizer. Note that because timeouts
     * and interrupts may occur at any time, a {@code true} return
     * does not guarantee that a future {@code signal} will awaken
     * any threads.  This method is designed primarily for use in
     * monitoring of the system state.
     *
     * @param condition the condition
     * @return {@code true} if there are any waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final boolean hasWaiters(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.hasWaiters();
    }

    /**
     * Returns an estimate of the number of threads waiting on the
     * given condition associated with this synchronizer. Note that
     * because timeouts and interrupts may occur at any time, the
     * estimate serves only as an upper bound on the actual number of
     * waiters.  This method is designed for use in monitoring of the
     * system state, not for synchronization control.
     *
     * @param condition the condition
     * @return the estimated number of waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final int getWaitQueueLength(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitQueueLength();
    }

    /**
     * Returns a collection containing those threads that may be
     * waiting on the given condition associated with this
     * synchronizer.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate. The elements of the
     * returned collection are in no particular order.
     *
     * @param condition the condition
     * @return the collection of threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final Collection<Thread> getWaitingThreads(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitingThreads();
    }

    /**
     * Condition implementation for a {@link
     * AbstractQueuedSynchronizer} serving as the basis of a {@link
     * Lock} implementation.
     *
     * <p>Method documentation for this class describes mechanics,
     * not behavioral specifications from the point of view of Lock
     * and Condition users. Exported versions of this class will in
     * general need to be accompanied by documentation describing
     * condition semantics that rely on those of the associated
     * {@code AbstractQueuedSynchronizer}.
     *
     * <p>This class is Serializable, but all fields are transient,
     * so deserialized conditions have no waiters.
     */
    public class ConditionObject implements Condition, java.io.Serializable {
        private static final long serialVersionUID = 1173984872572414699L;
        /** First node of condition queue. */
        private transient Node firstWaiter;
        /** Last node of condition queue. */
        private transient Node lastWaiter;

        /**
         * Creates a new {@code ConditionObject} instance.
         */
        public ConditionObject() { }

        // Internal methods

        /**
         * Adds a new waiter to wait queue.
         * @return its new wait node
         */
        private Node addConditionWaiter() {
            Node t = lastWaiter;
            // If lastWaiter is cancelled, clean out.
            if (t != null && t.waitStatus != Node.CONDITION) {
                unlinkCancelledWaiters();
                t = lastWaiter;
            }
            Node node = new Node(Thread.currentThread(), Node.CONDITION);
            if (t == null)
                firstWaiter = node;
            else
                t.nextWaiter = node;
            lastWaiter = node;
            return node;
        }

        /**
         * Removes and transfers nodes until hit non-cancelled one or
         * null. Split out from signal in part to encourage compilers
         * to inline the case of no waiters.
         * @param first (non-null) the first node on condition queue
         */
        private void doSignal(Node first) {
            do {
                if ( (firstWaiter = first.nextWaiter) == null)
                    lastWaiter = null;
                first.nextWaiter = null;
            } while (!transferForSignal(first) &&
                     (first = firstWaiter) != null);
        }

        /**
         * Removes and transfers all nodes.
         * @param first (non-null) the first node on condition queue
         */
        private void doSignalAll(Node first) {
            lastWaiter = firstWaiter = null;
            do {
                Node next = first.nextWaiter;
                first.nextWaiter = null;
                transferForSignal(first);
                first = next;
            } while (first != null);
        }

        /**
         * Unlinks cancelled waiter nodes from condition queue.
         * Called only while holding lock. This is called when
         * cancellation occurred during condition wait, and upon
         * insertion of a new waiter when lastWaiter is seen to have
         * been cancelled. This method is needed to avoid garbage
         * retention in the absence of signals. So even though it may
         * require a full traversal, it comes into play only when
         * timeouts or cancellations occur in the absence of
         * signals. It traverses all nodes rather than stopping at a
         * particular target to unlink all pointers to garbage nodes
         * without requiring many re-traversals during cancellation
         * storms.
         */
        private void unlinkCancelledWaiters() {
            Node t = firstWaiter;
            Node trail = null;
            while (t != null) {
                Node next = t.nextWaiter;
                if (t.waitStatus != Node.CONDITION) {
                    t.nextWaiter = null;
                    if (trail == null)
                        firstWaiter = next;
                    else
                        trail.nextWaiter = next;
                    if (next == null)
                        lastWaiter = trail;
                }
                else
                    trail = t;
                t = next;
            }
        }

        // public methods

        /**
         * Moves the longest-waiting thread, if one exists, from the
         * wait queue for this condition to the wait queue for the
         * owning lock.
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        public final void signal() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null)
                doSignal(first);
        }

        /**
         * Moves all threads from the wait queue for this condition to
         * the wait queue for the owning lock.
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        public final void signalAll() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null)
                doSignalAll(first);
        }

        /**
         * Implements uninterruptible condition wait.
         * <ol>
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * </ol>
         */
        public final void awaitUninterruptibly() {
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean interrupted = false;
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this);
                if (Thread.interrupted())
                    interrupted = true;
            }
            if (acquireQueued(node, savedState) || interrupted)
                selfInterrupt();
        }

        /*
         * For interruptible waits, we need to track whether to throw
         * InterruptedException, if interrupted while blocked on
         * condition, versus reinterrupt current thread, if
         * interrupted while blocked waiting to re-acquire.
         */

        /** Mode meaning to reinterrupt on exit from wait */
        private static final int REINTERRUPT =  1;
        /** Mode meaning to throw InterruptedException on exit from wait */
        private static final int THROW_IE    = -1;

        /**
         * Checks for interrupt, returning THROW_IE if interrupted
         * before signalled, REINTERRUPT if after signalled, or
         * 0 if not interrupted.
         */
        private int checkInterruptWhileWaiting(Node node) {
            return Thread.interrupted() ?
                (transferAfterCancelledWait(node) ? THROW_IE : REINTERRUPT) :
                0;
        }

        /**
         * Throws InterruptedException, reinterrupts current thread, or
         * does nothing, depending on mode.
         */
        private void reportInterruptAfterWait(int interruptMode)
            throws InterruptedException {
            if (interruptMode == THROW_IE)
                throw new InterruptedException();
            else if (interruptMode == REINTERRUPT)
                selfInterrupt();
        }

        /**
         * Implements interruptible condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled or interrupted.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         */
        public final void await() throws InterruptedException {
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null) // clean up if cancelled
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
        }

        /**
         * Implements timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         */
        public final long awaitNanos(long nanosTimeout)
                throws InterruptedException {
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return deadline - System.nanoTime();
        }

        /**
         * Implements absolute timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * <li> If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        public final boolean awaitUntil(Date deadline)
                throws InterruptedException {
            long abstime = deadline.getTime();
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (System.currentTimeMillis() > abstime) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                LockSupport.parkUntil(this, abstime);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        /**
         * Implements timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * <li> If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        public final boolean await(long time, TimeUnit unit)
                throws InterruptedException {
            long nanosTimeout = unit.toNanos(time);
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        //  support for instrumentation

        /**
         * Returns true if this condition was created by the given
         * synchronization object.
         *
         * @return {@code true} if owned
         */
        final boolean isOwnedBy(AbstractQueuedSynchronizer sync) {
            return sync == AbstractQueuedSynchronizer.this;
        }

        /**
         * Queries whether any threads are waiting on this condition.
         * Implements {@link AbstractQueuedSynchronizer#hasWaiters(ConditionObject)}.
         *
         * @return {@code true} if there are any waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final boolean hasWaiters() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    return true;
            }
            return false;
        }

        /**
         * Returns an estimate of the number of threads waiting on
         * this condition.
         * Implements {@link AbstractQueuedSynchronizer#getWaitQueueLength(ConditionObject)}.
         *
         * @return the estimated number of waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final int getWaitQueueLength() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            int n = 0;
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    ++n;
            }
            return n;
        }

        /**
         * Returns a collection containing those threads that may be
         * waiting on this Condition.
         * Implements {@link AbstractQueuedSynchronizer#getWaitingThreads(ConditionObject)}.
         *
         * @return the collection of threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final Collection<Thread> getWaitingThreads() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            ArrayList<Thread> list = new ArrayList<Thread>();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION) {
                    Thread t = w.thread;
                    if (t != null)
                        list.add(t);
                }
            }
            return list;
        }
    }

    /**
     * Setup to support compareAndSet. We need to natively implement
     * this here: For the sake of permitting future enhancements, we
     * cannot explicitly subclass AtomicInteger, which would be
     * efficient and useful otherwise. So, as the lesser of evils, we
     * natively implement using hotspot intrinsics API. And while we
     * are at it, we do the same for other CASable fields (which could
     * otherwise be done with atomic field updaters).
     */
    private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final long stateOffset;
    private static final long headOffset;
    private static final long tailOffset;
    private static final long waitStatusOffset;
    private static final long nextOffset;

    static {
        try {
            stateOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("state"));
            headOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("tail"));
            waitStatusOffset = unsafe.objectFieldOffset
                (Node.class.getDeclaredField("waitStatus"));
            nextOffset = unsafe.objectFieldOffset
                (Node.class.getDeclaredField("next"));

        } catch (Exception ex) { throw new Error(ex); }
    }

    /**
     * CAS head field. Used only by enq.
     */
    private final boolean compareAndSetHead(Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, null, update);
    }

    /**
     * CAS tail field. Used only by enq.
     */
    private final boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }

    /**
     * CAS waitStatus field of a node.
     */
    private static final boolean compareAndSetWaitStatus(Node node,
                                                         int expect,
                                                         int update) {
        return unsafe.compareAndSwapInt(node, waitStatusOffset,
                                        expect, update);
    }

    /**
     * CAS next field of a node.
     */
    private static final boolean compareAndSetNext(Node node,
                                                   Node expect,
                                                   Node update) {
        return unsafe.compareAndSwapObject(node, nextOffset, expect, update);
    }
}
