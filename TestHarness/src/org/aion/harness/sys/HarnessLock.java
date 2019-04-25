package org.aion.harness.sys;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.aion.harness.util.SimpleLog;

/**
 * A system-wide lock around resources used by the test harness.  Lock is implemented
 * by lock on the filesystem.  Intended to limit the number of concurrent executions
 * of test cases per single machine, so that orchestrators (i.e. Jenkins) can invoke
 * these Functional Tests without the kernel of each test clashing with one another
 * and/or using too much system resources.
 *
 * Each HarnessLock takes a name.  The lock for any particular name can only be
 * acquired once (until it is released).  Two HarnessLock objects with the same
 * name refer to the same lock.
 *
 * This is not intended to provide a lock to be used within tests.  It should only
 * be used by the test harness itself to ensure no system runs two instances of
 * test harness concurrently.  One HarnessLock object should not be acquired by
 * multiple threads (but the underlying file can be).
 */
public class HarnessLock {
    private final String name;
    private final static SimpleLog log = new SimpleLog(HarnessLock.class.getName());
    private final String fsPrefix;

    private volatile FileChannel channel;
    private volatile FileLock lock;

    /** The dir in which lock files are stored */
    public static final String DEFAULT_FS_PREFIX = "/var/lock/AionTestHarness";

    /**
     * Construct lock for the given name (but does not acquire it)
     *
     * @param name Name of the lock
     * @throws IOException if error with lock file
     */
    public HarnessLock(String name) throws IOException {
        this(DEFAULT_FS_PREFIX, name);
    }

    /**
     * Construct lock for the given name (but does not acquire it)
     *
     * @param name Name of the lock
     * @param prefix directory in which to put locks
     * @throws IOException if error with lock file
     */
    public HarnessLock(String prefix, String name) throws IOException {
        this.name = name;
//        this.log = new SimpleLog(getClass().getName());
        this.lock = null;
        this.fsPrefix = prefix;
    }

    /**
     * Acquire lock, blocking until it is available.
     *
     * If already acquired, nothing is done.
     * @throws IOException if error with lock file
     */
    public void acquire() throws IOException {
        if(! isAcquired()) {
            RandomAccessFile file = getLockFileIfFilesystemOk(fsPrefix, name);
            FileChannel chan = file.getChannel();
            lock = chan.lock();
            channel = chan;
        }
    }

    /**
     * Try to acquire lock (non-blocking) and return true if acquired or false otherwise.
     *
     * If already acquired, no work is done and returns true.
     *
     * @return whether lock acquired
     * @throws IOException if error with lock file
     */
    public boolean tryAcquire() throws IOException {
        if (!isAcquired()) {
            RandomAccessFile file = getLockFileIfFilesystemOk(fsPrefix, name);
            FileChannel chan = file.getChannel();
            lock = chan.tryLock();
            if (lock != null) {
                channel = chan;
                return true;
            } else {
                return false;
            }
        } else {
            return true;
        }
    }

    /**
     * Try to acquire lock, blocking until the given time duration has elapsed
     * and return true if acquired or false otherwise
     *
     * If already acquired, no work is done and returns {@link #isAcquired()}
     *
     * @param duration timeout duration value
     * @param unit timeout duration units
     * @return whether lock acquired
     */
    public boolean tryAcquire(long duration, TimeUnit unit) throws IOException {
        List<IOException> exceptionHolder = new LinkedList<>();
        Thread acquirer = new Thread(() -> {
           try {
               acquire();
           } catch(FileLockInterruptionException flie) {
               // if we get here, it's because FileLock#lock() throws this when
               // it is blocked and receives an interrupt.  nothing to do, since
               // we're no longer blocked.  just clear the interrupt flag to
               // indicate it was dealt with.
               Thread.interrupted();
           } catch(IOException ex) {
               // other exceptions need to be reported
               exceptionHolder.add(ex);
           }
        });

        acquirer.start();
        try {
            acquirer.join(unit.toMillis(duration));
        } catch(InterruptedException ie) {
            // if we get here, the acquirer was not blocked on locking the file
            // (otherwise it would have thrown FileLockInterruptionException instead).
            // it's possible that the lock was obtained right before the interruption.
            // we should assume the whole lock attempt failed and set all the class
            // variables back to their original values.
            release();
            ie.printStackTrace();
        }

        if(acquirer.isAlive()) {
            acquirer.interrupt();
        }
        if(! exceptionHolder.isEmpty()) {
            log.log(String.format(
                    "acquirer thread threw %d exceptions; re-throwing them",
                    exceptionHolder.size()));
            throw exceptionHolder.get(0);
        }

        return isAcquired();
    }

    /**
     * Release the lock (does nothing if lock not acquired)
     */
    public void release() throws IOException {
        try {
            if(lock != null) {
                lock.release(); // releasing multiple times has no effect
            }
        } catch(IOException ioe) {
            log.log(
                "Error while releasing lock (lock file may be in broken state)");
            throw ioe;
        } finally {
            // if release() threw, presume it's actually either released or
            // that channel closing below will succeed (which will release
            // the lock at the OS level).
            lock = null;
        }

        try {
            if(channel != null) {
                channel.close(); // closing multiple times has no effect
            }
        } catch(IOException ioe) {
            log.log(
                    "Error while closing channel (lock file may be in broken state)");
            throw ioe;
        } finally {
            channel = null;
        }
    }

    /**
     * @return Whether lock is acquired
     */
    public boolean isAcquired() {
        return lock != null;
    }

    /**
     * @return prefix of lock file (i.e. the directory containing it)
     */
    public String getPrefix() {
        return fsPrefix;
    }

    /**
     * @implNote synchronized in case someone tries to call acquire() from multiple
     * threads -- not the correct way to use this class, but avoid doing bad
     * operations to the filesystem just in case it happens.
     */
    private static synchronized RandomAccessFile
    getLockFileIfFilesystemOk(String prefix, String name) throws IOException  {
        File tmp = new File(prefix);
        if(! tmp.exists()) {
            if(! tmp.mkdirs()) {
                throw new IOException("Failed to create lock directory at location " +
                    tmp.getAbsolutePath());
            }
        }

        if(! tmp.canWrite() || ! tmp.canRead()) {
            throw new IOException(
                "Cannot read or write to lock directory at location " +
                    tmp.getAbsolutePath());
        }

        File lockFile = tmp.toPath().resolve(name).toFile();
        if(! lockFile.exists()) {
            if(! lockFile.createNewFile()) {
                throw new IOException("Failed to create lock file at location " +
                    tmp.getAbsolutePath());
            }
        }

        RandomAccessFile ret = new RandomAccessFile(lockFile, "rw");
        log.log("HarnessLock#getLockFileIfFilesystemOk: " + lockFile.getCanonicalPath());
        System.out.println("HarnessLock#getLockFileIfFilesystemOk: " + lockFile.getCanonicalPath());
        return ret;
    }
}
