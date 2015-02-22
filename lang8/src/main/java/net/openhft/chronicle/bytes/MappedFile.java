package net.openhft.chronicle.bytes;

import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.ReferenceCounted;
import net.openhft.chronicle.core.ReferenceCounter;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class MappedFile implements ReferenceCounted {
    private final ReferenceCounter refCount = ReferenceCounter.onReleased(this::performRelease);

    private final RandomAccessFile raf;

    private final FileChannel fileChannel;
    private final long chunkSize;
    private final long overlapSize;
    private final List<WeakReference<MappedByteStore>> stores = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public MappedFile(RandomAccessFile raf, long chunkSize, long overlapSize) {
        this.raf = raf;
        this.fileChannel = raf.getChannel();
        this.chunkSize = chunkSize;
        this.overlapSize = overlapSize;
    }

    public static MappedFile of(String filename) throws FileNotFoundException {
        return of(filename, 64 << 20);
    }

    public static MappedFile of(String filename, long chunkSize) throws FileNotFoundException {
        return of(filename, chunkSize, chunkSize / 4);
    }

    public static MappedFile of(String filename, long chunkSize, long overlapSize) throws FileNotFoundException {
        return new MappedFile(new RandomAccessFile(filename, "rw"), chunkSize, overlapSize);
    }

    public BytesStore acquire(long position) throws IOException {
        if (closed.get())
            throw new IOException("Closed");
        int chunk = (int) (position / chunkSize);
        synchronized (stores) {
            while (stores.size() <= chunk) {
                stores.add(null);
            }
            WeakReference<MappedByteStore> mbsRef = stores.get(chunk);
            if (mbsRef != null) {
                MappedByteStore mbs = mbsRef.get();
                if (mbs != null && mbs.tryReserve()) {
                    return mbs;
                }
            }
            long minSize = (chunk + 1L) * chunkSize + overlapSize;
            long size = fileChannel.size();
            if (size < minSize) {
                // handle a possible race condition between processes.
                try (FileLock lock = fileChannel.lock()) {
                    size = fileChannel.size();
                    if (size < minSize) {
                        raf.setLength(minSize);
                    }
                }
            }
            long mappedSize = chunkSize + overlapSize;
            long address = OS.map(fileChannel, FileChannel.MapMode.READ_WRITE, chunk * chunkSize, mappedSize);
            MappedByteStore mbs2 = new MappedByteStore(this, chunk * chunkSize, address, mappedSize);
            stores.set(chunk, new WeakReference<>(mbs2));
            mbs2.reserve();
            return mbs2;
        }
    }

    @Override
    public void reserve() {
        refCount.reserve();
    }

    @Override
    public void release() {
        refCount.release();
    }

    @Override
    public long refCount() {
        return refCount.get();
    }

    public void close() {
        if (!closed.compareAndSet(false, true))
            return;
        synchronized (stores) {
            ReferenceCounted.releaseAll((List) stores);
        }
        refCount.release();
    }

    void performRelease() {
        for (int i = 0; i < stores.size(); i++) {
            WeakReference<MappedByteStore> storeRef = stores.get(i);
            if (storeRef == null)
                continue;
            MappedByteStore mbs = storeRef.get();
            if (mbs != null) {
                long count = mbs.refCount();
                if (count > 0) {
                    mbs.release();
                    if (count > 1)
                        continue;
                }
            }
            stores.set(i, null);
        }
        try {
            fileChannel.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String referenceCounts() {
        StringBuilder sb = new StringBuilder();
        sb.append("refCount: ").append(refCount());
        for (WeakReference<MappedByteStore> store : stores) {
            long count = 0;
            if (store != null) {
                MappedByteStore mbs = store.get();
                if (mbs != null)
                    count = mbs.refCount();
            }
            sb.append(", ").append(count);
        }
        return sb.toString();
    }
}
