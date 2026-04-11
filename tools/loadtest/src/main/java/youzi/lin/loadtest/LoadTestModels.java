package youzi.lin.loadtest;

record WsResult(String label, int concurrency, int measureSec, WsSnapshot metrics) {
}

record WsSnapshot(long sent,
                  long received,
                  long errors,
                  double sendP95Ms,
                  double sendP99Ms,
                  double recvDelayP95Ms,
                  double recvDelayP99Ms) {
}

record DbResult(int concurrency,
                int measureSec,
                long writeOps,
                long readOps,
                double writeP95Ms,
                double writeP99Ms,
                double readP95Ms,
                double readP99Ms,
                long writeErr,
                long readErr) {
}

