from pathlib import Path

root=Path("app/src/main/java/pl/folderlan/android")
gallery=root/"GalleryPanel.java"
transfer=root/"TransferService.java"
lan=root/"LanShare.java"
gradle=Path("app/build.gradle")

def one(text, old, new, what):
    found=text.count(old)
    if found!=1:
        raise RuntimeError("%s: expected 1 anchor, got %s: %r"%(what,found,old[:120]))
    return text.replace(old,new,1)

# The number displayed next to a checkbox is GridView's position+1.
# Take that exact number at the moment "Kopiuj" is pressed, BEFORE a
# background job or MediaStore reload can reorder the gallery.
g=gallery.read_text(encoding="utf-8")
g=one(g,
'''        final boolean video;
        Item(Uri uri, String name, long date, long mediaId, boolean video) {''',
'''        final boolean video;
        int galleryNumber;
        Item(Uri uri, String name, long date, long mediaId, boolean video) {''',
"GalleryPanel.Item number")
g=one(g,
'''            for (Item item : photos) if (selected.contains(item.uri)) chosen.add(item);
            setCopying(true);
            handler.onCopy(chosen);''',
'''            for (int index=0; index<photos.size(); index++) {
                Item item=photos.get(index);
                if (selected.contains(item.uri)) {
                    // Exactly the (index+1) printed on this thumbnail.
                    item.galleryNumber=index+1;
                    chosen.add(item);
                }
            }
            setCopying(true);
            handler.onCopy(chosen);''',
"snapshot actual thumbnail numbers")
assert 'holder.tick.setText((position+1)+" "+(marked ? "☑" : "□"));' in g
gallery.write_text(g,encoding="utf-8")

# Preserve the exact gallery ordinal in the durable JSON queue, so it still
# matches after minimizing the app, Android process recreation or SMB failover.
t=transfer.read_text(encoding="utf-8")
t=one(t,
'''        final String timeLabel;
        Item(Uri uri,String name,boolean video,boolean camera,String timeLabel) {
            this.uri=uri;this.name=name;this.video=video;this.camera=camera;this.timeLabel=timeLabel;
        }''',
'''        final String timeLabel;
        final int galleryNumber;
        final String batchFolder;
        Item(Uri uri,String name,boolean video,boolean camera,String timeLabel) {
            this(uri,name,video,camera,timeLabel,0,"");
        }
        Item(Uri uri,String name,boolean video,boolean camera,String timeLabel,
             int galleryNumber,String batchFolder) {
            this.uri=uri;this.name=name;this.video=video;this.camera=camera;
            this.timeLabel=timeLabel;this.galleryNumber=galleryNumber;
            this.batchFolder=batchFolder;
        }''',
"queued item holds exact visible index")
t=one(t,
'''                .put("video",video).put("camera",camera).put("time",timeLabel);''',
'''                .put("video",video).put("camera",camera).put("time",timeLabel)
                .put("galleryNumber",galleryNumber).put("batchFolder",batchFolder);''',
"persist visible ordinal")
t=one(t,
'''                obj.optBoolean("camera"),obj.optString("time",""));''',
'''                obj.optBoolean("camera"),obj.optString("time",""),
                obj.optInt("galleryNumber",0),obj.optString("batchFolder",""));''',
"restore visible ordinal")
t=one(t,
'''        JSONArray list=new JSONArray();
        for(GalleryPanel.Item item:photos) {
            try { list.put(new Item(item.uri,item.name,item.video,false,"").json()); }
            catch (Exception ignored) {}
        }
        sendQueue(ctx,list);''',
'''        JSONArray list=new JSONArray();
        // A separate folder for each copy operation avoids silently
        // overwriting older "1.jpg" when the gallery changes order.
        // The user-visible ordinal is preserved WITHIN that operation.
        String series="Seria_"+new SimpleDateFormat("HH-mm-ss",Locale.ROOT)
            .format(new Date())+"_"+UUID.randomUUID().toString().substring(0,8);
        for(GalleryPanel.Item item:photos) {
            try { list.put(new Item(item.uri,item.name,item.video,false,"",
                item.galleryNumber,series).json()); }
            catch (Exception ignored) {}
        }
        sendQueue(ctx,list);''',
"send selected numbers as durable batch")
t=one(t,
'''    private String numberedFilename(SharedPreferences prefs,Item item,int cursor) {
        String date=new SimpleDateFormat("yyyyMMdd",Locale.ROOT).format(new Date());''',
'''    private String numberedFilename(SharedPreferences prefs,Item item,int cursor) {
        // Do not renumber gallery pictures according to the transfer counter.
        if(!item.camera && item.galleryNumber>0)
            return item.galleryNumber+extension(item);
        // Camera pictures retain their separate consecutive numbering.
        String date=new SimpleDateFormat("yyyyMMdd",Locale.ROOT).format(new Date());''',
"exact filename from thumbnail")
t=one(t,
'''                            if(item.camera)session.uploadCamera(input,target,cancelled);
                            else session.uploadGallery(input,target,cancelled);''',
'''                            if(item.camera)session.uploadCamera(input,target,cancelled);
                            else if(item.galleryNumber>0 && !item.batchFolder.isEmpty())
                                session.uploadGalleryBatch(input,target,item.batchFolder,cancelled);
                            else session.uploadGallery(input,target,cancelled);''',
"SMB upload preserving exact number")
assert 'String key=(item.camera ? "seq_camera_" : "seq_")+date;' in t
assert 'String target=item.camera ? "Zdjęcie z kamerki\\\\"+' in t
transfer.write_text(t,encoding="utf-8")

# The dated folder still exists. Within it, each copying batch is
# "Seria_<time>_<uuid>" and contains exactly 1.jpg,2.jpg,5.jpg,8.jpg...
# Using FILE_OVERWRITE_IF only in that unique batch makes retries idempotent:
# the same gallery position is written to the same name, never to "5_1.jpg".
l=lan.read_text(encoding="utf-8")
anchor='''    void uploadCamera(InputStream in,String relative,AtomicBoolean cancelled) throws Exception {'''
addition='''    void uploadGalleryBatch(InputStream in,String fileName,String batchFolder,
                            AtomicBoolean cancelled) throws Exception {
        if(batchFolder==null || !batchFolder.matches("Seria_[0-9]{2}-[0-9]{2}-[0-9]{2}_[a-f0-9]{8}"))
            throw new IllegalArgumentException("Nieprawidłowy identyfikator kopiowania");
        if(!fileName.matches("[1-9][0-9]*\\\\.[a-z0-9]{2,5}"))
            throw new IllegalArgumentException("Brak numeru zdjęcia z galerii");
        String day=galleryFolderForToday();
        mkdir(day);
        String folder=day+"\\\\"+batchFolder;
        mkdir(folder);
        uploadInFolder(in,folder,fileName,cancelled,true);
    }
'''
l=one(l,anchor,addition+anchor,"SMB per-selection folder")

# Keep old behavior unchanged for camera and old queued work, but
# enable fixed names for the new gallery batch only.
l=one(l,
'''        uploadInFolder(in,galleryFolderForToday(),fileName,cancelled);''',
'''        uploadInFolder(in,galleryFolderForToday(),fileName,cancelled,false);''',
"original gallery wrapper")
l=one(l,
'''        uploadInFolder(in,folder,filename,cancelled);''',
'''        uploadInFolder(in,folder,filename,cancelled,false);''',
"original camera wrapper")
l=one(l,
'''    private void uploadInFolder(InputStream in, String folder, String fileName, AtomicBoolean cancelled) throws Exception {''',
'''    private void uploadInFolder(InputStream in, String folder, String fileName,
                                AtomicBoolean cancelled, boolean fixedName) throws Exception {''',
"add fixed-name flag")
l=one(l,
'''        if (share.fileExists(path)) {''',
'''        if (!fixedName && share.fileExists(path)) {''',
"keep old collision handling")
# The method previously creates FILE_CREATE, which fails on an existing
# same-batch file after a crashed or repeated upload. Rewrite only fixedName.
l=one(l,
'''                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_CREATE,
                    EnumSet.noneOf(SMB2CreateOptions.class))) {''',
'''                    SMB2ShareAccess.ALL,
                    fixedName ? SMB2CreateDisposition.FILE_OVERWRITE_IF : SMB2CreateDisposition.FILE_CREATE,
                    EnumSet.noneOf(SMB2CreateOptions.class))) {''',
"safe batch retry filename")
lan.write_text(l,encoding="utf-8")

b=gradle.read_text(encoding="utf-8")
assert "versionCode 22" in b and "versionName '17.1'" in b
b=b.replace("versionCode 22","versionCode 23").replace("versionName '17.1'","versionName '17.2'")
gradle.write_text(b,encoding="utf-8")

# IMPORTANT negative regression checks.
assert 'holder.tick.setText((position+1)' in g
assert "selected.removeAll(successes);" not in g
assert 'String key=(item.camera ? "seq_camera_" : "seq_")+date;' in t
assert 'item.galleryNumber+extension(item)' in t
assert "uploadGalleryBatch" in t and "uploadGalleryBatch" in l
assert "transferLoop" in t
assert (root/"FindPhoneService.java").exists()
print("PASS gallery 1,2,3,5,8,9 -> same SMB filenames in this upload session.")
print("PASS durable queue preserves index, camera numbering and alarm unchanged.")
print("PASS separate batch folder prevents overwrites of earlier uploads.")
