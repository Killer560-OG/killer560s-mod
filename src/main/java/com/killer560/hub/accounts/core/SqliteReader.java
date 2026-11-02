package com.killer560.hub.accounts.core;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A minimal, strictly read-only reader for one rowid table of a SQLite 3 database file, written for the Modrinth App's
 * {@code app.db} (Minecraft ships no SQLite driver, and a native one is a crash risk inside the game). It follows the
 * published file format (sqlite.org/fileformat2.html): the 100-byte header, table b-tree pages (interior 0x05, leaf
 * 0x0D), records with their serial types, and overflow chains. A database in WAL mode keeps its newest pages in
 * {@code <db>-wal} until a checkpoint, so every committed frame of the WAL whose salts and running checksum are valid is
 * laid over the main file, exactly as a reader would see the database. Both files are opened for READ only and are
 * never written, locked or truncated; the {@code -shm} index is not touched at all.
 * <p>
 * Not supported (not needed here): WITHOUT ROWID tables, index b-trees, and reading while a page is mid-write - a read
 * that trips over a torn page throws {@link IOException} and the caller just reports it.
 */
public final class SqliteReader implements Closeable {

    private static final byte[] MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    private static final int WAL_MAGIC_LE = 0x377f0682;
    private static final int WAL_MAGIC_BE = 0x377f0683;
    /** A malformed or looping file must not hang the game: no table here is anywhere near this many pages. */
    private static final int MAX_PAGES_VISITED = 200_000;

    private final FileChannel db;
    private final int pageSize;
    private final int usable;
    private final Charset charset;
    /** Page number -> byte offset of that page's newest committed copy in the WAL. */
    private final Map<Integer, Long> walPages = new HashMap<>();
    private FileChannel wal;

    private SqliteReader(FileChannel db, Path walPath) throws IOException {
        this.db = db;
        ByteBuffer header = read(db, 0, 100);
        for (int i = 0; i < MAGIC.length; i++) {
            if (header.get(i) != MAGIC[i]) {
                throw new IOException("not a SQLite 3 database");
            }
        }
        int ps = header.getShort(16) & 0xFFFF;
        this.pageSize = ps == 1 ? 65536 : ps;
        if (this.pageSize < 512 || Integer.bitCount(this.pageSize) != 1) {
            throw new IOException("bad page size " + this.pageSize);
        }
        this.usable = this.pageSize - (header.get(20) & 0xFF);
        int enc = header.getInt(56);
        this.charset = enc == 2 ? StandardCharsets.UTF_16LE : enc == 3 ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_8;
        if (walPath != null && Files.isRegularFile(walPath) && Files.size(walPath) >= 32) {
            this.wal = FileChannel.open(walPath, StandardOpenOption.READ);
            loadWal();
        }
    }

    /** Opens {@code dbFile} (and {@code dbFile + "-wal"} when present) for reading. */
    public static SqliteReader open(Path dbFile) throws IOException {
        FileChannel ch = FileChannel.open(dbFile, StandardOpenOption.READ);
        try {
            return new SqliteReader(ch, dbFile.resolveSibling(dbFile.getFileName() + "-wal"));
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            this.db.close();
        } finally {
            if (this.wal != null) {
                this.wal.close();
            }
        }
    }

    // ---- WAL ----------------------------------------------------------------------------------------------------------

    private void loadWal() throws IOException {
        ByteBuffer h = read(this.wal, 0, 32);
        int magic = h.getInt(0);
        if (magic != WAL_MAGIC_LE && magic != WAL_MAGIC_BE) {
            return;
        }
        boolean bigEndianSums = magic == WAL_MAGIC_BE;
        if (h.getInt(8) != this.pageSize) {
            return;
        }
        int salt1 = h.getInt(16);
        int salt2 = h.getInt(20);
        int[] sum = checksum(h, 0, 24, 0, 0, bigEndianSums);
        if (sum[0] != h.getInt(24) || sum[1] != h.getInt(28)) {
            return;
        }
        long size = this.wal.size();
        long frameSize = 24L + this.pageSize;
        Map<Integer, Long> pending = new HashMap<>();
        for (long off = 32; off + frameSize <= size; off += frameSize) {
            ByteBuffer frame = read(this.wal, off, (int) frameSize);
            if (frame.getInt(8) != salt1 || frame.getInt(12) != salt2) {
                break;
            }
            sum = checksum(frame, 0, 8, sum[0], sum[1], bigEndianSums);
            sum = checksum(frame, 24, this.pageSize, sum[0], sum[1], bigEndianSums);
            if (sum[0] != frame.getInt(16) || sum[1] != frame.getInt(20)) {
                break;
            }
            pending.put(frame.getInt(0), off + 24);
            if (frame.getInt(4) != 0) { // a commit frame: everything up to here is a finished transaction
                this.walPages.putAll(pending);
                pending.clear();
            }
        }
    }

    /** SQLite's WAL checksum: pairs of 32-bit words, summed with each other's running value. */
    private static int[] checksum(ByteBuffer buf, int from, int len, int s0, int s1, boolean bigEndian) {
        ByteBuffer b = buf.duplicate().order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        for (int i = from; i + 8 <= from + len; i += 8) {
            s0 += b.getInt(i) + s1;
            s1 += b.getInt(i + 4) + s0;
        }
        return new int[] {s0, s1};
    }

    // ---- pages --------------------------------------------------------------------------------------------------------

    private ByteBuffer page(int number) throws IOException {
        if (number < 1) {
            throw new IOException("bad page number " + number);
        }
        Long walOff = this.walPages.get(number);
        if (walOff != null) {
            return read(this.wal, walOff, this.pageSize);
        }
        return read(this.db, (long) (number - 1) * this.pageSize, this.pageSize);
    }

    private static ByteBuffer read(FileChannel ch, long position, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length);
        while (buf.hasRemaining()) {
            int n = ch.read(buf, position + buf.position());
            if (n < 0) {
                throw new IOException("unexpected end of file");
            }
        }
        buf.flip();
        return buf.order(ByteOrder.BIG_ENDIAN);
    }

    // ---- tables -------------------------------------------------------------------------------------------------------

    /**
     * Every row of the rowid table {@code table}, as column name -> value (Long, Double, String, byte[] or null), in
     * rowid order. A row stored before a later {@code ALTER TABLE ADD COLUMN} simply lacks that column. Throws when
     * the table does not exist.
     */
    public List<Map<String, Object>> readTable(String table) throws IOException {
        Integer root = null;
        String sql = null;
        for (Object[] row : scan(1)) {
            // sqlite_schema: type, name, tbl_name, rootpage, sql
            if (row.length >= 5 && "table".equals(row[0]) && table.equalsIgnoreCase(String.valueOf(row[1]))) {
                root = row[3] instanceof Long l ? l.intValue() : null;
                sql = row[4] instanceof String s ? s : null;
                break;
            }
        }
        if (root == null || sql == null) {
            throw new IOException("no table " + table);
        }
        List<String> columns = columnNames(sql);
        if (sql.toUpperCase(Locale.ROOT).contains("WITHOUT ROWID")) {
            throw new IOException(table + " is a WITHOUT ROWID table");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : scan(root)) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                m.put(columns.get(i), i < row.length ? row[i] : null);
            }
            out.add(m);
        }
        return out;
    }

    /** Walks the table b-tree rooted at {@code rootPage} and decodes every leaf cell's record. */
    private List<Object[]> scan(int rootPage) throws IOException {
        List<Object[]> rows = new ArrayList<>();
        ArrayList<Integer> stack = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        stack.add(rootPage);
        while (!stack.isEmpty()) {
            int number = stack.remove(stack.size() - 1);
            if (!seen.add(number) || seen.size() > MAX_PAGES_VISITED) {
                throw new IOException("b-tree loop at page " + number);
            }
            ByteBuffer p = page(number);
            int hdr = number == 1 ? 100 : 0;
            int type = p.get(hdr) & 0xFF;
            int cells = p.getShort(hdr + 3) & 0xFFFF;
            if (type == 0x05) {
                int right = p.getInt(hdr + 8);
                // Pushed in reverse so pages pop in key (rowid) order: the right-most child goes in first.
                stack.add(right);
                for (int i = cells - 1; i >= 0; i--) {
                    int cell = p.getShort(hdr + 12 + 2 * i) & 0xFFFF;
                    stack.add(p.getInt(cell));
                }
            } else if (type == 0x0D) {
                for (int i = 0; i < cells; i++) {
                    int cell = p.getShort(hdr + 8 + 2 * i) & 0xFFFF;
                    rows.add(readLeafCell(p, cell));
                }
            } else {
                throw new IOException("page " + number + " is not a table b-tree page (type " + type + ")");
            }
        }
        return rows;
    }

    private Object[] readLeafCell(ByteBuffer p, int cell) throws IOException {
        long[] v = varint(p, cell);
        long payloadSize = v[0];
        int pos = cell + (int) v[1];
        long[] rowid = varint(p, pos);
        pos += (int) rowid[1];
        if (payloadSize < 0 || payloadSize > 1_000_000_000L) {
            throw new IOException("bad payload size " + payloadSize);
        }
        int maxLocal = this.usable - 35;
        int minLocal = ((this.usable - 12) * 32 / 255) - 23;
        int local;
        if (payloadSize <= maxLocal) {
            local = (int) payloadSize;
        } else {
            local = (int) (minLocal + ((payloadSize - minLocal) % (this.usable - 4)));
            if (local > maxLocal) {
                local = minLocal;
            }
        }
        byte[] payload = new byte[(int) payloadSize];
        p.get(pos, payload, 0, local);
        if (local < payloadSize) {
            int next = p.getInt(pos + local);
            int filled = local;
            int hops = 0;
            while (filled < payloadSize) {
                if (next == 0 || ++hops > MAX_PAGES_VISITED) {
                    throw new IOException("broken overflow chain");
                }
                ByteBuffer o = page(next);
                int n = (int) Math.min(this.usable - 4, payloadSize - filled);
                o.get(4, payload, filled, n);
                filled += n;
                next = o.getInt(0);
            }
        }
        return decodeRecord(ByteBuffer.wrap(payload));
    }

    private Object[] decodeRecord(ByteBuffer r) throws IOException {
        long[] h = varint(r, 0);
        int headerSize = (int) h[0];
        int hp = (int) h[1];
        List<Long> types = new ArrayList<>();
        while (hp < headerSize) {
            long[] t = varint(r, hp);
            types.add(t[0]);
            hp += (int) t[1];
        }
        Object[] out = new Object[types.size()];
        int dp = headerSize;
        for (int i = 0; i < types.size(); i++) {
            long t = types.get(i);
            if (t == 0) {
                out[i] = null;
            } else if (t >= 1 && t <= 6) {
                int len = t == 5 ? 6 : t == 6 ? 8 : (int) t; // 1-4 bytes, then 6 and 8
                long val = r.get(dp); // sign-extended first byte
                for (int k = 1; k < len; k++) {
                    val = (val << 8) | (r.get(dp + k) & 0xFF);
                }
                out[i] = val;
                dp += len;
            } else if (t == 7) {
                out[i] = r.getDouble(dp);
                dp += 8;
            } else if (t == 8) {
                out[i] = 0L;
            } else if (t == 9) {
                out[i] = 1L;
            } else if (t >= 12) {
                int len = (int) ((t - (t % 2 == 0 ? 12 : 13)) / 2);
                if (dp + len > r.limit()) {
                    throw new IOException("record runs past its payload");
                }
                byte[] bytes = new byte[len];
                r.get(dp, bytes);
                out[i] = t % 2 == 0 ? bytes : new String(bytes, this.charset);
                dp += len;
            } else {
                throw new IOException("reserved serial type " + t);
            }
        }
        return out;
    }

    /** A SQLite varint at {@code pos}: {value, byte length}. */
    private static long[] varint(ByteBuffer b, int pos) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            int c = b.get(pos + i) & 0xFF;
            v = (v << 7) | (c & 0x7F);
            if ((c & 0x80) == 0) {
                return new long[] {v, i + 1};
            }
        }
        v = (v << 8) | (b.get(pos + 8) & 0xFF);
        return new long[] {v, 9};
    }

    /**
     * The column names of a {@code CREATE TABLE} statement, in order: the parenthesized body split on top-level commas,
     * table constraints (PRIMARY KEY, UNIQUE, CHECK, FOREIGN KEY, CONSTRAINT) skipped, and quotes taken off the names.
     */
    static List<String> columnNames(String sql) {
        StringBuilder clean = new StringBuilder();
        for (String line : sql.split("\n")) {
            int c = line.indexOf("--");
            clean.append(c >= 0 ? line.substring(0, c) : line).append('\n');
        }
        String s = clean.toString();
        int open = s.indexOf('(');
        int close = s.lastIndexOf(')');
        List<String> cols = new ArrayList<>();
        if (open < 0 || close <= open) {
            return cols;
        }
        String body = s.substring(open + 1, close);
        int depth = 0;
        int start = 0;
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                parts.add(body.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(body.substring(start));
        for (String part : parts) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            String upper = t.toUpperCase(Locale.ROOT);
            if (upper.startsWith("PRIMARY KEY") || upper.startsWith("UNIQUE") || upper.startsWith("CHECK")
                    || upper.startsWith("FOREIGN KEY") || upper.startsWith("CONSTRAINT")) {
                continue;
            }
            String name;
            char q = t.charAt(0);
            if (q == '"' || q == '`' || q == '[') {
                char end = q == '[' ? ']' : q;
                int e = t.indexOf(end, 1);
                name = e > 0 ? t.substring(1, e) : t.substring(1);
            } else {
                int e = 0;
                while (e < t.length() && !Character.isWhitespace(t.charAt(e))) {
                    e++;
                }
                name = t.substring(0, e);
            }
            cols.add(name);
        }
        return cols;
    }
}
