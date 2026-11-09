package pl.folderlan.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import fi.iki.elonen.NanoHTTPD;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Explicit, foreground LAN sharing of one SAF tree. No SMB credentials are used here.
 * HTTP is deliberately limited to trusted Wi-Fi; it does not provide transport encryption.
 */
public final class PhoneFolderServer {
    private static final long MAX_UPLOAD = 2L * 1024 * 1024 * 1024;
    private final Context context;
    private final Uri treeUri;
    private final Object fileLock = new Object();
    private volatile Server server;
    private volatile String url;
    private ConnectivityManager.NetworkCallback callback;
    private volatile LinkAddress boundAddress;
    private volatile Network boundNetwork;

    public PhoneFolderServer(Context context, Uri treeUri) {
        this.context=context.getApplicationContext();this.treeUri=treeUri;
    }
    public synchronized void start() throws IOException {
        if(isRunning())return;
        DocumentFile root=DocumentFile.fromTreeUri(context,treeUri);
        if(root==null || !root.exists() || !root.isDirectory() || !root.canRead()) throw new IOException("Brak dostępu do wybranego folderu telefonu. Wybierz go ponownie.");
        boundAddress=wifiAddress();
        String host=boundAddress.getAddress().getHostAddress();
        Server candidate=new Server(host,PhoneServerSecurity.token(),root);
        try {
            candidate.start(15000,true);
            server=candidate;url="http://"+host+":"+candidate.getListeningPort()+"/"+candidate.token+"/";
            ConnectivityManager connectivity=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            callback=new ConnectivityManager.NetworkCallback(){
                @Override public void onLost(Network network){if(network.equals(boundNetwork))stop();}
                @Override public void onLinkPropertiesChanged(Network network,LinkProperties properties){if(network.equals(boundNetwork) && !bindingStillValid())stop();}
                @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){if(network.equals(boundNetwork) && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))stop();}
            };
            // Local-only Wi-Fi may not be the default network when cellular has Internet.
            connectivity.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),callback);
            if(!bindingStillValid())throw new IOException("Połączenie Wi-Fi zostało przerwane.");
        }catch(Exception error){candidate.stop();stop();throw new IOException("Nie udało się uruchomić udostępniania telefonu.");}
    }
    private boolean bindingStillValid(){
        try{
            ConnectivityManager connectivity=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities capabilities=connectivity.getNetworkCapabilities(boundNetwork);
            LinkProperties properties=connectivity.getLinkProperties(boundNetwork);
            if(capabilities==null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || properties==null)return false;
            for(LinkAddress address:properties.getLinkAddresses())if(address.getAddress().equals(boundAddress.getAddress()) && address.getPrefixLength()==boundAddress.getPrefixLength())return true;
            return false;
        }catch(Exception ignored){return false;}
    }
    public String getUrl(){return isRunning()?url:null;}
    public boolean isRunning(){Server current=server;return current!=null && current.isAlive();}
    public synchronized void stop(){
        Server current=server;server=null;url=null;
        if(current!=null)current.stop();
        if(callback!=null){try{((ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(callback);}catch(Exception ignored){}callback=null;}
    }
    private LinkAddress wifiAddress() throws IOException {
        ConnectivityManager connectivity=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        List<Network> candidates=new ArrayList<>();
        Network active=connectivity.getActiveNetwork();
        if(active!=null)candidates.add(active);
        for(Network network:connectivity.getAllNetworks())if(!candidates.contains(network))candidates.add(network);
        boolean foundWifi=false;
        for(Network network:candidates){
            NetworkCapabilities capabilities=connectivity.getNetworkCapabilities(network);
            LinkProperties properties=connectivity.getLinkProperties(network);
            if(capabilities==null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || properties==null)continue;
            foundWifi=true;
            for(LinkAddress address:properties.getLinkAddresses()){
                InetAddress host=address.getAddress();
                if(host instanceof Inet4Address && !host.isLoopbackAddress() && (host.isSiteLocalAddress() || host.isLinkLocalAddress())){boundNetwork=network;return address;}
            }
        }
        if(!foundWifi)throw new IOException("Połącz telefon i komputer z tą samą siecią Wi-Fi.");
        throw new IOException("Wi-Fi nie ma prywatnego adresu IPv4 potrzebnego do udostępniania.");
    }
    private boolean localPeer(String peer){
        try{
            InetAddress remote=InetAddress.getByName(peer);
            if(!(remote instanceof Inet4Address) || remote.isLoopbackAddress() || (!remote.isSiteLocalAddress() && !remote.isLinkLocalAddress()))return false;
            byte[] a=remote.getAddress(),b=boundAddress.getAddress().getAddress();int bits=boundAddress.getPrefixLength();
            for(int i=0;i<a.length;i++){int mask=bits>=8?255:bits<=0?0:(255 << (8-bits))&255;if((a[i]&mask)!=(b[i]&mask))return false;bits-=8;}
            return true;
        }catch(Exception ignored){return false;}
    }
    private final class Server extends NanoHTTPD {
        final String token;
        final String host;
        final DocumentFile root;
        final BoundedRunner runner=new BoundedRunner();
        Server(String host,String token,DocumentFile root){
            super(host,0);this.host=host;this.token=token;this.root=root;
            setAsyncRunner(runner);
            // serve() streams raw bodies and never calls parseBody: no plaintext temporary uploads.
            Logger.getLogger(NanoHTTPD.class.getName()).setLevel(Level.OFF);
        }
        @Override public Response serve(IHTTPSession request){
            String expectedHost=host+":"+getListeningPort();
            if(!localPeer(request.getRemoteIpAddress()) || !expectedHost.equals(request.getHeaders().get("host")) || !PhoneServerSecurity.tokenPath(request.getUri(),token))return message(Response.Status.NOT_FOUND,"Brak dostępu.");
            try{
                String path=PhoneServerSecurity.normalize(parameter(request,"path",""));
                String action=parameter(request,"action","");
                if(request.getMethod()==Method.GET){
                    if("download".equals(action))return download(path);
                    if(!action.isEmpty())return message(Response.Status.BAD_REQUEST,"Nieznana operacja.");
                    return page(path);
                }
                if(request.getMethod()!=Method.POST)return message(Response.Status.METHOD_NOT_ALLOWED,"Użyj przeglądarki komputera.");
                if(!PhoneServerSecurity.sameOrigin(request.getHeaders().get("origin"),"http://"+expectedHost))return message(Response.Status.FORBIDDEN,"Żądanie z innej strony zostało odrzucone.");
                if(!root.canWrite())return message(Response.Status.FORBIDDEN,"Folder telefonu jest tylko do odczytu.");
                synchronized(fileLock){
                    switch(action){
                        case "upload":upload(request,path,PhoneServerSecurity.name(parameter(request,"name","")));break;
                        case "mkdir":mkdir(path,PhoneServerSecurity.name(parameter(request,"name","")));break;
                        case "rename":rename(path,PhoneServerSecurity.name(parameter(request,"name","")));break;
                        case "delete":delete(path);break;
                        default:return message(Response.Status.BAD_REQUEST,"Nieznana operacja.");
                    }
                }
                return message(Response.Status.OK,"Gotowe.");
            }catch(IllegalArgumentException error){return message(Response.Status.BAD_REQUEST,"Nieprawidłowa ścieżka lub nazwa.");}
            catch(Conflict error){return message(Response.Status.CONFLICT,error.getMessage());}
            catch(SecurityException error){return message(Response.Status.FORBIDDEN,"Android nie udzielił dostępu. Ponownie wybierz folder w aplikacji.");}
            catch(Exception error){return message(Response.Status.INTERNAL_ERROR,"Nie wykonano operacji. Sprawdź dostęp do folderu i wolne miejsce w telefonie.");}
        }
        private String parameter(IHTTPSession request,String key,String fallback){
            List<String> values=request.getParameters().get(key);return values==null || values.isEmpty()?fallback:values.get(0);
        }
        private DocumentFile resolve(String path) throws IOException {
            DocumentFile document=root;
            if(path.isEmpty())return document;
            for(String part:path.split("/")){
                if(!document.isDirectory())throw new IOException("Nie jest folderem.");
                document=document.findFile(part);
                if(document==null || !document.exists())throw new IOException("Brak pliku.");
            }
            return document;
        }
        private Response download(String path) throws IOException {
            DocumentFile file=resolve(path);if(!file.isFile())return message(Response.Status.NOT_FOUND,"Nie znaleziono pliku.");
            InputStream input=context.getContentResolver().openInputStream(file.getUri());if(input==null)throw new IOException("Brak strumienia.");
            Response response=security(newChunkedResponse(Response.Status.OK,"application/octet-stream",input));
            response.addHeader("Content-Disposition","attachment; filename=\"plik\"; filename*=UTF-8''"+PhoneServerSecurity.encode(file.getName()==null?"plik":file.getName()));
            return response;
        }
        private void upload(IHTTPSession request,String path,String name) throws Exception {
            if(request.getHeaders().containsKey("transfer-encoding"))throw new Conflict("Wysyłanie strumieni bez rozmiaru nie jest obsługiwane.");
            String sizeValue=request.getHeaders().get("content-length");
            if(sizeValue==null)throw new Conflict("Brak rozmiaru pliku.");
            long size=Long.parseLong(sizeValue);if(size<0 || size>MAX_UPLOAD)throw new Conflict("Maksymalny rozmiar wysyłanego pliku to 2 GiB.");
            DocumentFile directory=resolve(path);if(!directory.isDirectory())throw new IOException("Nie jest folderem.");
            if(directory.findFile(name)!=null)throw new Conflict("Plik już istnieje. Zmień nazwę; nie nadpisujemy danych.");
            DocumentFile temp=directory.createFile("application/octet-stream",".folder-lan-"+PhoneServerSecurity.token()+".tmp");
            if(temp==null)throw new IOException("Nie utworzono pliku.");
            boolean complete=false;
            try{
                OutputStream output=context.getContentResolver().openOutputStream(temp.getUri(),"w");
                if(output==null)throw new IOException("Brak strumienia.");
                try(output){
                    byte[] buffer=new byte[65536];long remaining=size;InputStream input=request.getInputStream();
                    while(remaining>0){int count=input.read(buffer,0,(int)Math.min(buffer.length,remaining));if(count<0)throw new IOException("Przerwano wysyłanie.");output.write(buffer,0,count);remaining-=count;}
                    output.flush();
                }
                if(directory.findFile(name)!=null)throw new Conflict("Nazwa została zajęta podczas wysyłania. Nie nadpisano danych.");
                if(!temp.renameTo(name) || !name.equals(temp.getName()))throw new IOException("Nie zmieniono nazwy pliku tymczasowego.");
                complete=true;
            }finally{if(!complete)temp.delete();}
        }
        private void mkdir(String path,String name) throws Exception {
            DocumentFile directory=resolve(path);if(!directory.isDirectory())throw new IOException("Nie jest folderem.");
            if(directory.findFile(name)!=null)throw new Conflict("Plik lub folder o tej nazwie już istnieje.");
            if(directory.createDirectory(name)==null)throw new IOException("Nie utworzono folderu.");
        }
        private void rename(String path,String name) throws Exception {
            if(path.isEmpty())throw new Conflict("Nie można zmienić nazwy głównego folderu.");
            DocumentFile document=resolve(path);DocumentFile parent=resolve(PhoneServerSecurity.parent(path));
            if(parent.findFile(name)!=null)throw new Conflict("Ta nazwa jest już używana.");
            if(!document.renameTo(name))throw new IOException("Nie zmieniono nazwy.");
        }
        private void delete(String path) throws Exception {
            if(path.isEmpty())throw new Conflict("Główny folder jest chroniony.");
            DocumentFile document=resolve(path);
            if(document.isDirectory() && document.listFiles().length>0)throw new Conflict("Folder nie jest pusty. Usuń jego pliki osobno.");
            if(!document.delete())throw new IOException("Nie usunięto pliku.");
        }
        private Response page(String path) throws IOException {
            DocumentFile directory=resolve(path);if(!directory.isDirectory())return message(Response.Status.NOT_FOUND,"Nie znaleziono folderu.");
            DocumentFile[] files=directory.listFiles();if(files.length>10000)return message(Response.Status.CONFLICT,"Zbyt dużo plików w folderze. Podziel je na mniejsze foldery.");
            Arrays.sort(files,Comparator.comparing((DocumentFile file)->!file.isDirectory()).thenComparing(file->file.getName()==null?"":file.getName(),String.CASE_INSENSITIVE_ORDER));
            String nonce=PhoneServerSecurity.token();String rootPath="/"+token+"/";
            StringBuilder html=new StringBuilder("<!doctype html><html lang=\"pl\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Folder LAN — telefon</title>");
            html.append("<style nonce=\"").append(nonce).append("\">body{font:16px system-ui,sans-serif;background:#f4f7fa;color:#123;max-width:960px;margin:auto;padding:24px}header{background:#0d283e;color:white;padding:24px;border-radius:18px}h1{margin:0}small{color:#526574}table{width:100%;border-collapse:collapse;background:white}td,th{text-align:left;padding:14px;border-bottom:1px solid #e4ebef}a{color:#087f8c}button{border:0;background:#087f8c;color:white;padding:10px 14px;border-radius:8px;cursor:pointer;margin:3px}button.danger{background:#a52b35}input{padding:10px}nav{margin:18px 0}#status{padding:12px;color:#087f8c}.size{white-space:nowrap}footer{margin-top:24px;color:#526574}td:first-child{word-break:break-all}</style>");
            html.append("<body data-base=\"").append(rootPath).append("\" data-path=\"").append(PhoneServerSecurity.html(path)).append("\"><header><h1>Folder LAN</h1><p>Folder telefonu · ").append(PhoneServerSecurity.html(directory.getName())).append("</p></header><nav>");
            if(!path.isEmpty())html.append("<a href=\"").append(rootPath).append("?path=").append(PhoneServerSecurity.encode(PhoneServerSecurity.parent(path))).append("\">← Folder nadrzędny</a> · ");
            html.append("<a href=\"").append(rootPath).append("\">Folder główny</a></nav><p>Ścieżka: /").append(PhoneServerSecurity.html(path)).append("</p>");
            if(root.canWrite())html.append("<button id=\"mkdir\">Nowy folder</button><label><input id=\"file\" type=\"file\"></label><button id=\"upload\">Wyślij do telefonu</button>");
            html.append("<div id=\"status\" role=\"status\"></div><table><thead><tr><th>Nazwa</th><th>Rozmiar</th><th>Operacje</th></tr></thead><tbody>");
            for(DocumentFile file:files){
                String name=file.getName();if(name==null)continue;String child;
                try{child=PhoneServerSecurity.child(path,name);}catch(IllegalArgumentException invalid){continue;}
                String link=rootPath+"?path="+PhoneServerSecurity.encode(child)+(file.isDirectory()?"":"&amp;action=download");
                html.append("<tr><td>").append(file.isDirectory()?"📁 ":"📄 ").append("<a href=\"").append(link).append("\">").append(PhoneServerSecurity.html(name)).append("</a></td><td class=\"size\">").append(file.isDirectory()?"Folder":file.length()+" B").append("</td><td>");
                if(root.canWrite())html.append("<button data-op=\"rename\" data-path=\"").append(PhoneServerSecurity.html(child)).append("\" data-name=\"").append(PhoneServerSecurity.html(name)).append("\">Zmień nazwę</button><button class=\"danger\" data-op=\"delete\" data-path=\"").append(PhoneServerSecurity.html(child)).append("\" data-name=\"").append(PhoneServerSecurity.html(name)).append("\">Usuń</button>");
                html.append("</td></tr>");
            }
            html.append("</tbody></table><footer>Dostęp aktywny tylko podczas udostępniania w aplikacji. Link daje dostęp do wybranego folderu. HTTP nie szyfruje danych — używaj tylko zaufanego Wi-Fi. Wysyłanie: do 2 GiB na plik, bez nadpisywania. Usuwane są tylko puste foldery.</footer>");
            html.append("<script nonce=\"").append(nonce).append("\">const base=document.body.dataset.base,here=document.body.dataset.path;const status=document.getElementById('status');let busy=false;async function send(action,path,name,body){if(busy)return;busy=true;status.textContent='Trwa przesyłanie lub wykonywanie operacji…';try{const q=new URLSearchParams({action,path});if(name!==undefined)q.set('name',name);const r=await fetch(base+'?'+q,{method:'POST',body:body,credentials:'omit',redirect:'error',headers:body?{'Content-Type':'application/octet-stream'}:{}});const text=await r.text();if(!r.ok)throw new Error(text);location.reload();}catch(e){status.textContent=e.message||'Nie udało się wykonać operacji.';busy=false;}}document.querySelectorAll('[data-op]').forEach(b=>b.addEventListener('click',()=>{if(b.dataset.op==='delete'){if(confirm('Usunąć '+b.dataset.name+'? Tej operacji nie można cofnąć.'))send('delete',b.dataset.path);}else{const name=prompt('Nowa nazwa',b.dataset.name);if(name&&name!==b.dataset.name)send('rename',b.dataset.path,name);}}));const mk=document.getElementById('mkdir');if(mk)mk.onclick=()=>{const name=prompt('Nazwa nowego folderu');if(name)send('mkdir',here,name);};const upload=document.getElementById('upload');if(upload)upload.onclick=()=>{const f=document.getElementById('file').files[0];if(!f){alert('Wybierz plik.');return;}if(f.size>2147483648){alert('Plik jest większy niż 2 GiB.');return;}send('upload',here,f.name,f);};</script></body></html>");
            Response response=security(newFixedLengthResponse(Response.Status.OK,"text/html; charset=utf-8",html.toString()));
            response.addHeader("Content-Security-Policy","default-src 'none'; script-src 'nonce-"+nonce+"'; style-src 'nonce-"+nonce+"'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
            return response;
        }
        private Response message(Response.Status status,String text){return security(newFixedLengthResponse(status,"text/plain; charset=utf-8",text));}
        private Response security(Response response){
            response.addHeader("Cache-Control","no-store, max-age=0");response.addHeader("Referrer-Policy","no-referrer");response.addHeader("X-Content-Type-Options","nosniff");response.addHeader("X-Frame-Options","DENY");response.closeConnection(true);return response;
        }
    }
    private static final class Conflict extends IOException {Conflict(String message){super(message);}}
    private static final class BoundedRunner implements NanoHTTPD.AsyncRunner {
        private final Set<NanoHTTPD.ClientHandler> clients=Collections.synchronizedSet(new HashSet<>());
        private final ThreadPoolExecutor threads=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8), task->{Thread thread=new Thread(task,"FolderLAN-HTTP");thread.setDaemon(true);return thread;});
        @Override public void closeAll(){synchronized(clients){for(NanoHTTPD.ClientHandler client:new ArrayList<>(clients))client.close();clients.clear();}threads.shutdownNow();}
        @Override public void closed(NanoHTTPD.ClientHandler client){clients.remove(client);}
        @Override public void exec(NanoHTTPD.ClientHandler client){clients.add(client);try{threads.execute(client);}catch(java.util.concurrent.RejectedExecutionException full){clients.remove(client);client.close();}}
    }
}
