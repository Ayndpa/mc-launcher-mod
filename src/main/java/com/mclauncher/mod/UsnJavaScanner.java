package com.mclauncher.mod;

import com.sun.jna.Native;
import com.sun.jna.Memory;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 全盘 Java 搜索 —— 移植自 WinUI-Craft-Launcher 的 JavaScanner：
 * 通过 FSCTL_ENUM_USN_DATA 直接枚举 NTFS MFT 找出所有 java.exe，
 * 再逐个用 java -version 验证。比递归遍历目录快几个数量级。
 */
public final class UsnJavaScanner {

    private static final int BUFFER_SIZE = 4 * 1024 * 1024;
    private static final int FSCTL_ENUM_USN_DATA = 0x000900B3;
    private static final int ERROR_HANDLE_EOF = 38;
    private static final int DRIVE_FIXED = 3;
    private static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;

    private UsnJavaScanner() {
    }

    public record Progress(String phase, String detail, int found) {
    }

    public interface ProgressFn {
        void accept(Progress p);
    }

    private record DirNode(long parentFrn, String name) {
    }

    private record Hit(long parentFrn, String name) {
    }

    /** 扫描所有固定 NTFS 卷，返回候选 java.exe 路径（去重） */
    public static List<String> scan(ProgressFn progress) {
        List<String> candidates = new ArrayList<>();
        for (char letter : fixedNtfsDrives()) {
            scanVolume(letter, candidates, progress);
        }
        Set<String> seen = new HashSet<>();
        List<String> unique = new ArrayList<>();
        for (String p : candidates) {
            String k = p.toLowerCase(Locale.ROOT);
            if (seen.add(k)) {
                unique.add(p);
            }
        }
        return unique;
    }

    private static List<Character> fixedNtfsDrives() {
        List<Character> out = new ArrayList<>();
        for (String d : Kernel32Util.getLogicalDriveStrings()) {
            if (d.isBlank()) {
                continue;
            }
            char letter = Character.toUpperCase(d.charAt(0));
            if (Kernel32.INSTANCE.GetDriveType(d) == DRIVE_FIXED) {
                out.add(letter);
            }
        }
        return out;
    }

    private static void scanVolume(char letter, List<String> candidates, ProgressFn progress) {
        String root = letter + ":\\";
        char[] fsName = new char[64];
        if (!Kernel32.INSTANCE.GetVolumeInformation(root, null, 0, null, null, null, fsName, fsName.length)) {
            return;
        }
        if (!new String(fsName).trim().equalsIgnoreCase("NTFS")) {
            return;
        }

        WinNT.HANDLE hVol = Kernel32.INSTANCE.CreateFile(
                "\\\\.\\C:".replace('C', letter),
                WinNT.GENERIC_READ,
                WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE,
                null,
                WinNT.OPEN_EXISTING,
                WinNT.FILE_ATTRIBUTE_NORMAL,
                null);
        if (hVol == null || hVol.equals(WinBase.INVALID_HANDLE_VALUE)) {
            return;
        }

        if (progress != null) {
            progress.accept(new Progress("scanning", "正在扫描 " + root, candidates.size()));
        }

        Map<Long, DirNode> dirs = new HashMap<>();
        List<Hit> hits = new ArrayList<>();

        try {
            long startFrn = 0;
            while (true) {
                byte[] in = new byte[28];
                putLongLE(in, 0, startFrn);
                putLongLE(in, 8, 0);                // LowUsn
                putLongLE(in, 16, Long.MAX_VALUE);  // HighUsn
                // MFT_ENUM_DATA_V1 的版本字段是 WORD（2 字节）
                putShortLE(in, 24, 2);              // MinMajorVersion
                putShortLE(in, 26, 3);              // MaxMajorVersion
                Memory inBuf = new Memory(in.length);
                inBuf.write(0, in, 0, in.length);

                byte[] buffer = new byte[BUFFER_SIZE];
                Memory outBuf = new Memory(buffer.length);
                IntByReference bytesReturned = new IntByReference();
                boolean ok = Kernel32.INSTANCE.DeviceIoControl(
                        hVol,
                        FSCTL_ENUM_USN_DATA,
                        inBuf, in.length,
                        outBuf, buffer.length,
                        bytesReturned,
                        null);                if (!ok) {
                    if (Native.getLastError() == ERROR_HANDLE_EOF) {
                        break;
                    }
                    break; // 其它错误：放弃该卷
                }
                int bytes = bytesReturned.getValue();
                if (bytes < 8) {
                    break;
                }
                outBuf.read(0, buffer, 0, bytes);
                startFrn = getLongLE(buffer, 0);
                processUsnBuffer(buffer, bytes, dirs, hits);
            }
        } finally {
            Kernel32Util.closeHandle(hVol);
        }

        for (Hit hit : hits) {
            String path = buildPath(letter, hit.parentFrn(), hit.name(), dirs);
            if (!path.isEmpty()) {
                candidates.add(path);
            }
        }
    }

    private static void processUsnBuffer(byte[] data, int byteCount, Map<Long, DirNode> dirs, List<Hit> hits) {
        int offset = 8; // 跳过 next USN
        while (offset + 8 <= byteCount) {
            int recordLength = getUnsignedShortLE(data, offset);
            if (recordLength == 0 || offset + recordLength > byteCount) {
                break;
            }
            int major = getUnsignedShortLE(data, offset + 4);
            if (major != 2 && major != 3) {
                offset += recordLength;
                continue;
            }
            long frn;
            long parent;
            int fileAttributes;
            int fileNameLength;
            int fileNameOffset;
            if (major == 2) {
                // USN_RECORD_V2: RecordLength(2) Major(2) Minor(2) FileAttributes(2)
                // FileReferenceNumber(8)@8 ParentFileReferenceNumber(8)@16
                // ... FileAttributes(4)@52 FileNameLength(2)@56 FileNameOffset(2)@58
                frn = getLongLE(data, offset + 8);
                parent = getLongLE(data, offset + 16);
                fileAttributes = getIntLE(data, offset + 52);
                fileNameLength = getUnsignedShortLE(data, offset + 56);
                fileNameOffset = getUnsignedShortLE(data, offset + 58);
            } else {
                // USN_RECORD_V3: RecordLength(2) Major(2) Minor(2) FileAttributes(2)
                // FileReferenceNumber FILE_ID_128(16)@8 Parent(16)@24
                // ... FileAttributes(4)@68 FileNameLength(2)@72 FileNameOffset(2)@74
                frn = getLongLE(data, offset + 8);
                parent = getLongLE(data, offset + 24);
                fileAttributes = getIntLE(data, offset + 68);
                fileNameLength = getUnsignedShortLE(data, offset + 72);
                fileNameOffset = getUnsignedShortLE(data, offset + 74);
            }
            if (fileNameOffset + fileNameLength <= recordLength) {
                String name = new String(data, offset + fileNameOffset, fileNameLength, StandardCharsets.UTF_16LE);
                if ((fileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0) {
                    dirs.put(frn, new DirNode(parent, name));
                } else if (name.equalsIgnoreCase("java.exe")) {
                    hits.add(new Hit(parent, name));
                }
            }
            offset += recordLength;
        }
    }

    private static String buildPath(char letter, long parentFrn, String fileName, Map<Long, DirNode> dirs) {
        List<String> parts = new ArrayList<>();
        parts.add(fileName);
        long frn = parentFrn;
        boolean reachedKnownParent = false;
        for (int depth = 0; depth < 512 && frn != 0; depth++) {
            DirNode node = dirs.get(frn);
            if (node == null) {
                break;
            }
            reachedKnownParent = true;
            if (!node.name().isEmpty() && !node.name().equals(".") && !node.name().equals("..")) {
                parts.add(node.name());
            }
            long next = node.parentFrn();
            if (next == frn) {
                break;
            }
            frn = next;
        }
        if (!reachedKnownParent && parentFrn != 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(letter).append(":\\");
        for (int i = parts.size() - 1; i >= 0; i--) {
            if (i != parts.size() - 1) {
                sb.append('\\');
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static void putLongLE(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) {
            b[off + i] = (byte) (v >>> (8 * i));
        }
    }

    private static void putIntLE(byte[] b, int off, int v) {
        for (int i = 0; i < 4; i++) {
            b[off + i] = (byte) (v >>> (8 * i));
        }
    }

    private static void putShortLE(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
    }

    private static long getLongLE(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }

    private static int getIntLE(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }

    private static int getUnsignedShortLE(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }
}
