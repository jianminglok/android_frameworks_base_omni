package android.content;

import com.google.android.collect.Maps;

import android.util.Pair;
import android.util.Log;
import android.accounts.Account;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Iterator;

/**
 *
 * @hide
 */
public class SyncQueue {
    private static final String TAG = "SyncManager";
    private SyncStorageEngine mSyncStorageEngine;

    // A Map of SyncOperations operationKey -> SyncOperation that is designed for
    // quick lookup of an enqueued SyncOperation.
    private final HashMap<String, SyncOperation> mOperationsMap = Maps.newHashMap();

    public SyncQueue(SyncStorageEngine syncStorageEngine) {
        mSyncStorageEngine = syncStorageEngine;
        ArrayList<SyncStorageEngine.PendingOperation> ops
                = mSyncStorageEngine.getPendingOperations();
        final int N = ops.size();
        for (int i=0; i<N; i++) {
            SyncStorageEngine.PendingOperation op = ops.get(i);
            SyncOperation syncOperation = new SyncOperation(
                    op.account, op.syncSource, op.authority, op.extras, 0 /* delay */);
            syncOperation.expedited = op.expedited;
            syncOperation.pendingOperation = op;
            add(syncOperation, op);
        }
    }

    public boolean add(SyncOperation operation) {
        return add(operation, null /* this is not coming from the database */);
    }

    private boolean add(SyncOperation operation,
            SyncStorageEngine.PendingOperation pop) {
        // - if an operation with the same key exists and this one should run earlier,
        //   update the earliestRunTime of the existing to the new time
        // - if an operation with the same key exists and if this one should run
        //   later, ignore it
        // - if no operation exists then add the new one
        final String operationKey = operation.key;
        final SyncOperation existingOperation = mOperationsMap.get(operationKey);

        if (existingOperation != null) {
            boolean changed = false;
            if (existingOperation.expedited == operation.expedited) {
                final long newRunTime =
                        Math.min(existingOperation.earliestRunTime, operation.earliestRunTime);
                if (existingOperation.earliestRunTime != newRunTime) {
                    existingOperation.earliestRunTime = newRunTime;
                    changed = true;
                }
            } else {
                if (operation.expedited) {
                    existingOperation.expedited = true;
                    changed = true;
                }
            }
            return changed;
        }

        operation.pendingOperation = pop;
        if (operation.pendingOperation == null) {
            pop = new SyncStorageEngine.PendingOperation(
                            operation.account, operation.syncSource,
                            operation.authority, operation.extras, operation.expedited);
            pop = mSyncStorageEngine.insertIntoPending(pop);
            if (pop == null) {
                throw new IllegalStateException("error adding pending sync operation "
                        + operation);
            }
            operation.pendingOperation = pop;
        }

        mOperationsMap.put(operationKey, operation);
        return true;
    }

    /**
     * Remove the specified operation if it is in the queue.
     * @param operation the operation to remove
     */
    public void remove(SyncOperation operation) {
        SyncOperation operationToRemove = mOperationsMap.remove(operation.key);
        if (operationToRemove == null) {
            return;
        }
        if (!mSyncStorageEngine.deleteFromPending(operationToRemove.pendingOperation)) {
            final String errorMessage = "unable to find pending row for " + operationToRemove;
            Log.e(TAG, errorMessage, new IllegalStateException(errorMessage));
        }
    }

    /**
     * Find the operation that should run next. Operations are sorted by their earliestRunTime,
     * prioritizing expedited operations. The earliestRunTime is adjusted by the sync adapter's
     * backoff and delayUntil times, if any.
     * @return the operation that should run next and when it should run. The time may be in
     * the future. It is expressed in milliseconds since boot.
     */
    public Pair<SyncOperation, Long> nextOperation() {
        SyncOperation best = null;
        long bestRunTime = 0;
        for (SyncOperation op : mOperationsMap.values()) {
            long opRunTime = SyncManager.runTimeWithBackoffs(mSyncStorageEngine, op.account,
                    op.authority,
                    op.extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false),
                    op.earliestRunTime);
            // if the expedited state of both ops are the same then compare their runtime.
            // Otherwise the candidate is only better than the current best if the candidate
            // is expedited.
            if (best == null
                    || (best.expedited == op.expedited ? opRunTime < bestRunTime : op.expedited)) {
                best = op;
                bestRunTime = opRunTime;
            }
        }
        if (best == null) {
            return null;
        }
        return Pair.create(best, bestRunTime);
    }

    /**
     * Find and return the SyncOperation that should be run next and is ready to run.
     * @param now the current {@link android.os.SystemClock#elapsedRealtime()}, used to
     * decide if the sync operation is ready to run
     * @return the SyncOperation that should be run next and is ready to run.
     */
    public Pair<SyncOperation, Long> nextReadyToRun(long now) {
        Pair<SyncOperation, Long> nextOpAndRunTime = nextOperation();
        if (nextOpAndRunTime == null || nextOpAndRunTime.second > now) {
            return null;
        }
        return nextOpAndRunTime;
    }

    public void remove(Account account, String authority) {
        Iterator<Map.Entry<String, SyncOperation>> entries = mOperationsMap.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, SyncOperation> entry = entries.next();
            SyncOperation syncOperation = entry.getValue();
            if (account != null && !syncOperation.account.equals(account)) {
                continue;
            }
            if (authority != null && !syncOperation.authority.equals(authority)) {
                continue;
            }
            entries.remove();
            if (!mSyncStorageEngine.deleteFromPending(syncOperation.pendingOperation)) {
                final String errorMessage = "unable to find pending row for " + syncOperation;
                Log.e(TAG, errorMessage, new IllegalStateException(errorMessage));
            }
        }
    }

    public void dump(StringBuilder sb) {
        sb.append("SyncQueue: ").append(mOperationsMap.size()).append(" operation(s)\n");
        for (SyncOperation operation : mOperationsMap.values()) {
            sb.append(operation).append("\n");
        }
    }
}
