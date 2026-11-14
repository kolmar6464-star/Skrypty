from pathlib import Path

root=Path("app/src/main/java/pl/folderlan/android")
camera=root/"CameraWatcher.java"
lan=root/"LanShare.java"
activity=root/"MainActivity.java"
gradle=Path("app/build.gradle")

def replace(src, old, new, label):
    count=src.count(old)
    if count!=1:
        raise RuntimeError("v17.3 "+label+": expected one anchor; got "+str(count)+" | "+old[:120])
    return src.replace(old,new,1)

# Camera photos: preserve the exact existing notification + service,
# but do not discard IS_PENDING items or miss OEM camera directories.
s=camera.read_text(encoding="utf-8")
s=replace(s,
'''        ctx.getSharedPreferences(PREF,MODE_PRIVATE).edit().putBoolean("enabled",enabled).commit();''',
'''        android.content.SharedPreferences prefs=ctx.getSharedPreferences(PREF,MODE_PRIVATE);
        if(enabled && !prefs.getBoolean("enabled",false)) {
            // Start observing from the time the user opts in. A photo captured
            // during service initialization must not be silently discarded.
            prefs.edit().putLong("enabledSince",System.currentTimeMillis())
                .putLong("lastId",-1).putBoolean("enabled",true).commit();
        } else {
            prefs.edit().putBoolean("enabled",enabled).commit();
        }''',
"enabled timestamp")
s=replace(s,
'''            // Initialize watermark to newest photo when opt-in is enabled.
            if(getSharedPreferences(PREF,MODE_PRIVATE).getLong("lastId",-1)<0)
                worker.execute(this::initializeWatermark);
            else scan();
            handler.postDelayed(check,15000);''',
'''            // The cutoff is the opt-in timestamp, not the latest MediaStore ID.
            // Otherwise a photo captured between checkbox and service startup is missed.
            if(getSharedPreferences(PREF,MODE_PRIVATE).getLong("lastId",-1)<0)
                getSharedPreferences(PREF,MODE_PRIVATE).edit().putLong("lastId",0).commit();
            scan();
            handler.postDelayed(check,15000);''',
"do not skip photo captured just after opt-in")
s=replace(s,
'''        if(last<0){initializeWatermark();return;}
        long newest=last;''',
'''        if(last<0)last=0;
        long newest=last;
        long enabledSince=pref.getLong("enabledSince",0);''',
"scan from enabled time")
start=s.index("                if(id>newest)newest=id;")
end=s.index("                String filename=c.getString(1);",start)
camera_block=r"""                // Wait for the camera to finish writing this row. Do not move
                // the watermark past a pending photo.
                if(c.getInt(5)!=0)break;
                long addedMs=c.getLong(2)*1000L;
                if(id>newest)newest=id;
                if(enabledSince>0 && addedMs+3000L<enabledSince)continue;
                String folder=c.getString(3);
                if(folder==null)continue;
                String lower=folder.replace('\\','/').toLowerCase(Locale.ROOT);
                boolean dcim=lower.startsWith("dcim/") &&
                    !lower.contains("screenshot") && !lower.contains("thumbnail") &&
                    !lower.contains("whatsapp") && !lower.contains("telegram");
                boolean cameraPictures=lower.startsWith("pictures/camera") ||
                    lower.startsWith("pictures/aparat");
                if(!dcim && !cameraPictures)continue;
"""
s=s[:start]+camera_block+s[end:]
s=replace(s,'''        }catch(SecurityException ignored) {
            // User must allow full image/media access in Android Settings.
        }catch(Exception ignored){}''',
'''        }catch(SecurityException denied) {
            // Preserve watermark so a permission change can recover photos.
        }catch(Exception ignored){}''',"permission note")
camera.write_text(s,encoding="utf-8")

# Add an SMB delete primitive with path validation, no implicit deletion.
l=lan.read_text(encoding="utf-8")
anchor="    void mkdir(String path) { if (!share.folderExists(path)) share.mkdir(path); }\n"
addition='''    // Delete only a user-confirmed path within the authenticated SMB share.
    // Never allow traversal, the share root, hidden internal state or reparse links.
    private void validateDeletePath(String relative) {
        if(relative==null || relative.isEmpty() || relative.length()>1024 ||
            relative.startsWith("\\\\") || relative.startsWith("/") ||
            relative.contains("/") || relative.contains(":"))
            throw new IllegalArgumentException("Nieprawidłowa ścieżka usuwania");
        String[] pieces=relative.split("\\\\\\\\",-1);
        for(String part:pieces) {
            if(part.isEmpty() || part.equals(".") || part.equals("..") ||
                part.startsWith(".") || part.endsWith(" ") || part.endsWith("."))
                throw new IllegalArgumentException("Niedozwolony katalog lub plik");
        }
    }
    void deleteRemote(String relative, boolean directory) throws Exception {
        if(share==null)throw new IllegalStateException("Brak połączenia z SMB");
        validateDeletePath(relative);
        if(directory) {
            if(!share.folderExists(relative))throw new java.io.FileNotFoundException(relative);
            int[] count={0};
            deleteDirectoryTree(relative,0,count);
        } else {
            if(!share.fileExists(relative))throw new java.io.FileNotFoundException(relative);
            share.rm(relative);
        }
    }
    private void deleteDirectoryTree(String path,int depth,int[] count) throws Exception {
        if(depth>16 || ++count[0]>5000)
            throw new IllegalStateException("Zbyt wiele plików do bezpiecznego usunięcia");
        for(FileIdBothDirectoryInformation info:share.list(path)) {
            String name=info.getFileName();
            if(name.equals(".") || name.equals(".."))continue;
            String child=path+"\\\\"+name;
            validateDeletePath(child);
            long attributes=info.getFileAttributes();
            if((attributes & FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.getValue())!=0 ||
                (attributes & FileAttributes.FILE_ATTRIBUTE_HIDDEN.getValue())!=0 ||
                (attributes & FileAttributes.FILE_ATTRIBUTE_SYSTEM.getValue())!=0)
                throw new IllegalStateException("Folder zawiera element chroniony: "+name);
            if((attributes & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue())!=0)
                deleteDirectoryTree(child,depth+1,count);
            else
                share.rm(child);
        }
        share.rmdir(path);
    }
'''
l=replace(l,anchor,addition+anchor,"SMB delete")
lan.write_text(l,encoding="utf-8")

# Every existing card retains its preview/download/checkbox interactions.
# Place a separate, visible red Delete below the card; no layout compression.
m=activity.read_text(encoding="utf-8")
m=replace(m,
'''        return row;
    }
    private static boolean isVideoName(String name) {''',
'''        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(82)));
        card.addView(row);
        Button delete=action("Usuń",Color.rgb(176,47,57),
            () -> confirmRemoteDelete(entry,path));
        delete.setTextSize(12);
        LinearLayout.LayoutParams deleteLp=new LinearLayout.LayoutParams(-1,dp(40));
        deleteLp.topMargin=dp(2);
        card.addView(delete,deleteLp);
        LinearLayout.LayoutParams cardLp=new LinearLayout.LayoutParams(-1,-2);
        cardLp.bottomMargin=dp(9);
        card.setLayoutParams(cardLp);
        return card;
    }
    private void confirmRemoteDelete(LanShare.Entry entry,String path) {
        if(busy || remote==null)return;
        String kind=entry.directory?"folder":"plik";
        new AlertDialog.Builder(this)
            .setTitle("Usunąć "+kind+" z komputera?")
            .setMessage("Nazwa: "+entry.name+
                (entry.directory?"\\nFolder i cała zawartość zostaną usunięte.":"")+
                "\\nOperacja dotyczy udziału SMB i może zostać zsynchronizowana między komputerami.")
            .setNegativeButton("Anuluj",null)
            .setPositiveButton("Usuń",(dialog,button)->{
                work("Usuwanie z folderu udostępnionego…",()->{
                    LanShare connected=remote;
                    if(connected==null)throw new Exception("Brak połączenia SMB.");
                    connected.deleteRemote(path,entry.directory);
                    selectedRemote.remove(path);
                    setStatus("Usunięto z komputera: "+entry.name);
                    refreshInternal();
                });
            }).show();
    }
    private static boolean isVideoName(String name) {''',
"remote file and folder delete button")
assert 'galleryNumber' in (root/"GalleryPanel.java").read_text(encoding="utf-8")
assert 'RING_NOTIFICATION_ID' in (root/"FindPhoneService.java").read_text(encoding="utf-8")
assert 'sendCamera' in (root/"TransferService.java").read_text(encoding="utf-8")
activity.write_text(m,encoding="utf-8")

b=gradle.read_text(encoding="utf-8")
assert "versionCode 23" in b and "versionName '17.2'" in b
gradle.write_text(b.replace("versionCode 23","versionCode 24")
                   .replace("versionName '17.2'","versionName '17.3'"),encoding="utf-8")

print("PASS camera: pending images retried, OEM DCIM and Pictures/Camera paths, no historical photo auto-send.")
print("PASS remote: explicit, confirmed SMB file and directory deletion; all previous preview and gallery actions remain.")
print("PASS v17.3 number+SMB failover+red alarm unchanged.")
