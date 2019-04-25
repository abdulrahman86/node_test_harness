package org.aion.harness.sys;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.aion.harness.util.SimpleLog;

/**
 * Block until OS-level lock released for leveldb lock files.
 */
public class LeveldbLockAwaiter {
    private File databaseDir;
    private SimpleLog log;

    public static final int AWAIT_INTERVAL_SEC = 3;
    public static final int AWAIT_LIMIT_MIN = 5;
    public static final List<String> LOCK_FILES = Collections.unmodifiableList(
        List.of(
        "pendingBlock/index/LOCK",
        "state/LOCK",
        "index/LOCK",
        "block/LOCK",
        "transaction/LOCK",
        "contractIndex/LOCK",
        "contractPerformCode/LOCK",
        "details/LOCK",
        "storage/LOCK",
        "pendingtxPool/LOCK",
        "pendingtxCache/LOCK",
        "pendingBlock/level/LOCK",
        "pendingBlock/queue/LOCK",
        "graph/LOCK"
    ));

    /**
     * Constructor
     *
     * @param databaseDir root of the dir of the Aion database dir we're waiting on
     */
    public LeveldbLockAwaiter(String databaseDir) {
        this.databaseDir = new File(databaseDir);
        this.log = new SimpleLog(getClass().getName());
    }

    /**
     * Block for up to {@link #AWAIT_LIMIT_MIN} minutes until all files of
     * {@link #LOCK_FILES} are not locked.
     */
    public void await() throws IOException, InterruptedException {
        List<File> notChecked = LOCK_FILES.stream()
            .map(f -> new File(databaseDir + File.separator + f))
            .collect(Collectors.toList());

        long t0 = System.nanoTime();
        while(! notChecked.isEmpty()
            && System.nanoTime() - t0 < MINUTES.toNanos(AWAIT_LIMIT_MIN)) {

            File file = notChecked.get(0);
            if(file.exists() && checkIsFileLocked(file)) {
                log.log(String.format(
                    "Waiting for lock file to be unlocked: '%s' . [total lock waiting time so far: ~%s min, limit: ~%s min]",
                    file.getPath(),
                    NANOSECONDS.toMinutes(System.nanoTime() - t0),
                    AWAIT_LIMIT_MIN
                ));
                SECONDS.sleep(AWAIT_INTERVAL_SEC);
            } else {
                log.log("Lock file OK: " + file.getParent());
                notChecked.remove(0);
            }
        }
    }

    /** @return whether file is locked */
    private static boolean
    checkIsFileLocked(File file) throws IOException {
        try(FileChannel ch = new RandomAccessFile(file, "rw").getChannel()) {
            try(FileLock lock = ch.tryLock()) {
                return lock == null;
            }
        }
    }
}