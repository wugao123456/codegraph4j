package com.codegraph.sync;

import java.util.ArrayList;
import java.util.List;

/**
 * 同步操作的结果。
 */
public class SyncResult {

    private int filesChecked;
    private int filesAdded;
    private int filesModified;
    private int filesRemoved;
    private int nodesUpdated;
    private long durationMs;
    private List<String> changedFilePaths;

    public SyncResult() {
        this.changedFilePaths = new ArrayList<>();
    }

    public int getFilesChecked() {
        return filesChecked;
    }

    public void setFilesChecked(int filesChecked) {
        this.filesChecked = filesChecked;
    }

    public int getFilesAdded() {
        return filesAdded;
    }

    public void setFilesAdded(int filesAdded) {
        this.filesAdded = filesAdded;
    }

    public int getFilesModified() {
        return filesModified;
    }

    public void setFilesModified(int filesModified) {
        this.filesModified = filesModified;
    }

    public int getFilesRemoved() {
        return filesRemoved;
    }

    public void setFilesRemoved(int filesRemoved) {
        this.filesRemoved = filesRemoved;
    }

    public int getNodesUpdated() {
        return nodesUpdated;
    }

    public void setNodesUpdated(int nodesUpdated) {
        this.nodesUpdated = nodesUpdated;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public List<String> getChangedFilePaths() {
        return changedFilePaths;
    }

    public void setChangedFilePaths(List<String> changedFilePaths) {
        this.changedFilePaths = changedFilePaths;
    }

    public int getFilesChanged() {
        return filesAdded + filesModified + filesRemoved;
    }

    @Override
    public String toString() {
        return "SyncResult{" +
                "filesChecked=" + filesChecked +
                ", filesAdded=" + filesAdded +
                ", filesModified=" + filesModified +
                ", filesRemoved=" + filesRemoved +
                ", nodesUpdated=" + nodesUpdated +
                ", durationMs=" + durationMs +
                '}';
    }
}
