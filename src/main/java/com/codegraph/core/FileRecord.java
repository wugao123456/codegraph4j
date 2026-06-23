package com.codegraph.core;

import com.codegraph.core.types.Language;

import java.util.Objects;

public class FileRecord {
    private String filePath;
    private Language language;
    private long size;
    private long mtime;
    private String hash;
    private boolean isIgnored;
    private boolean isIndexable;
    private boolean isGenerated;
    private long indexedAt;
    private long updatedAt;

    public FileRecord() {
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Language getLanguage() {
        return language;
    }

    public void setLanguage(Language language) {
        this.language = language;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public long getMtime() {
        return mtime;
    }

    public void setMtime(long mtime) {
        this.mtime = mtime;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public boolean isIgnored() {
        return isIgnored;
    }

    public void setIgnored(boolean ignored) {
        isIgnored = ignored;
    }

    public boolean isIndexable() {
        return isIndexable;
    }

    public void setIndexable(boolean indexable) {
        isIndexable = indexable;
    }

    public boolean isGenerated() {
        return isGenerated;
    }

    public void setGenerated(boolean generated) {
        isGenerated = generated;
    }

    public long getIndexedAt() {
        return indexedAt;
    }

    public void setIndexedAt(long indexedAt) {
        this.indexedAt = indexedAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileRecord that = (FileRecord) o;
        return Objects.equals(filePath, that.filePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filePath);
    }

    @Override
    public String toString() {
        return "FileRecord{" +
                "filePath='" + filePath + '\'' +
                ", language=" + language +
                ", size=" + size +
                ", isIgnored=" + isIgnored +
                ", isIndexable=" + isIndexable +
                '}';
    }
}