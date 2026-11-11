from pathlib import Path
p=Path('app/src/main/java/pl/folderlan/android/LanShare.java')
s=p.read_text(encoding='utf-8')
def replace(old,new):
    global s
    if old not in s: raise RuntimeError("LanShare missing anchor: "+old[:100])
    s=s.replace(old,new,1)
replace('import java.io.InputStream;\n', 'import java.io.InputStream;\nimport java.io.InterruptedIOException;\nimport java.io.ByteArrayOutputStream;\n')
replace('import java.util.ArrayList;\n','import java.util.ArrayList;\nimport java.util.UUID;\nimport java.util.concurrent.atomic.AtomicBoolean;\nimport java.time.LocalDate;\nimport java.time.format.DateTimeFormatter;\n')
replace('            if (".".equals(name) || "..".equals(name)) continue;',
'''            int attributes = fi.getFileAttributes();
            if (".".equals(name) || "..".equals(name) || name.startsWith(".")) continue;
            if ((attributes & FileAttributes.FILE_ATTRIBUTE_HIDDEN.getValue()) != 0
                    || (attributes & FileAttributes.FILE_ATTRIBUTE_SYSTEM.getValue()) != 0) continue;''')
start=s.index('    void uploadGallery(InputStream in, String fileName)')
end=s.index('    void download(String path, OutputStream out)',start)
s=s[:start]+'''    String galleryFolderForToday() {
        LocalDate today = LocalDate.now();
        String day = today.format(DateTimeFormatter.ofPattern("EEEE", new Locale("pl", "PL")));
        day = day.substring(0, 1).toUpperCase(new Locale("pl", "PL")) + day.substring(1);
        return "Z_Telefonu - " + day + " - "
            + today.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
    }
    void uploadGallery(InputStream in, String fileName, AtomicBoolean cancelled) throws Exception {
        if (share == null) throw new IllegalStateException("Brak połączenia z komputerem");
        if (cancelled.get()) throw new InterruptedIOException("Kopiowanie anulowane.");
        String folder = galleryFolderForToday();
        mkdir(folder);
        String name = fileName.replaceAll("[\\\\\\\\/\\\\u0000-\\\\u001F:*?\\\\\\"<>|]", "_");
        if (name.equals(".") || name.equals("..") || name.trim().isEmpty()) name = "zdjecie";
        String path = folder + "\\\\" + name;
        if (share.fileExists(path)) {
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0,dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            int n=1;
            do { path = folder + "\\\\" + stem + "_" + n++ + ext; }
            while (share.fileExists(path));
        }
        boolean completed = false;
        boolean created = false;
        try {
            if (cancelled.get()) throw new InterruptedIOException("Kopiowanie anulowane.");
            try (File file = share.openFile(path,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_CREATE,
                    EnumSet.noneOf(SMB2CreateOptions.class))) {
                created = true;
                try (OutputStream out = file.getOutputStream()) {
                    byte[] chunk = new byte[65536];
                    int n;
                    while ((n = in.read(chunk)) != -1) {
                        if (cancelled.get()) throw new InterruptedIOException("Kopiowanie anulowane.");
                        out.write(chunk,0,n);
                    }
                    if (cancelled.get()) throw new InterruptedIOException("Kopiowanie anulowane.");
                    out.flush();
                }
            }
            completed = true;
        } finally {
            // Interrupted files must not remain as apparently complete backups.
            // Delete ONLY the newly created incomplete file on the SMB host.
            if (created && !completed) {
                try { share.rm(path); } catch (Exception ignored) {}
            }
        }
    }
    private static final String RESTORE_CONTROL =
            ".folderlan-c4453784df7d41e3be622d7d47ab1cb4.tmp";
    /** Same restore request marker as Invoke-ReplicaRestore in Windows PS1.
     *  Windows workers perform the synchronization; Android never downloads a mirror.
     */
    String requestWindowsSynchronization() throws Exception {
        if (share == null) throw new IllegalStateException("Brak połączenia");
        mkdir(RESTORE_CONTROL);
        String token = UUID.randomUUID().toString().replace("-", "");
        String fileName = RESTORE_CONTROL + "\\\\prosba.txt";
        try (File file = share.openFile(fileName,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.noneOf(SMB2CreateOptions.class));
             OutputStream output = file.getOutputStream()) {
            output.write(token.getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }
        return token;
    }
    boolean synchronizationCompleted(String token) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            download(RESTORE_CONTROL + "\\\\gotowe.txt", output);
            return token.equals(output.toString("UTF-8").trim());
        } catch (Exception ignored) { return false; }
    }
''' + s[end:]
p.write_text(s,encoding='utf-8')
assert 'readPairingAsset' not in s
print('LanShare v4: hidden files filter, cancellation, date folders, Windows sync request')
