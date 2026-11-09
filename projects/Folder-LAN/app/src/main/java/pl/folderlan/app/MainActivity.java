package pl.folderlan.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.database.Cursor;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int UPLOAD=11, DOWNLOAD=12, SHARE_PHONE=13;
    private static final int INK=0xff142c42, MUTED=0xff566b7e, TEAL=0xff087f8c, BG=0xfff4f7fa;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Set<String> legacyHosts=new HashSet<>();
    private final List<SmbRepository.Credentials> computers=new ArrayList<>();
    private CredentialStore store;
    private SmbRepository repository;
    private PhoneFolderServer phoneServer;
    private LinearLayout body;
    private TextView status;
    private ProgressBar progress;
    private String path="", pendingDownload, pendingUploadDirectory;
    private SmbRepository.Credentials active;
    private boolean busy, destroyed, filesScreen;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store=new CredentialStore(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        root.setPadding(dp(18),dp(16),dp(18),dp(8));
        root.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(dp(18),dp(8)+insets.getSystemWindowInsetTop(),dp(18),dp(8)+insets.getSystemWindowInsetBottom());return insets;});
        TextView brand=text("FOLDER LAN",12,TEAL);brand.setLetterSpacing(.16f);brand.setTypeface(null,Typeface.BOLD);root.addView(brand);
        TextView title=text("Twoje pliki.\nW Twojej sieci.",30,INK);title.setTypeface(null,Typeface.BOLD);title.setPadding(0,dp(8),0,dp(8));root.addView(title);
        status=text("Połącz telefon i komputer z tą samą siecią Wi-Fi.",14,MUTED);root.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);progress.setVisibility(View.GONE);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(5)));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(0,dp(16),0,dp(16));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        try { computers.addAll(store.load()); } catch(Exception e) {
            showError("Nie można odczytać zapisanego parowania. Dodaj komputer ponownie. Poprzednie dane możesz usunąć w menu aplikacji.");
        }
        home();
        body.postDelayed(()->{if(!destroyed&&!busy&&computers.isEmpty()&&wifiNetwork()!=null)discover(false);},600);
    }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
    private void gap(){Space s=new Space(this);body.addView(s,new LinearLayout.LayoutParams(1,dp(12)));}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(16),dp(14),dp(16),dp(12));GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(18));c.setBackground(bg);body.addView(c,new LinearLayout.LayoutParams(-1,-2));return c;}
    private Button button(String caption,LinearLayout parent,View.OnClickListener action){Button b=new Button(this);b.setText(caption);b.setAllCaps(false);b.setTextColor(TEAL);b.setOnClickListener(v->{if(!busy)action.onClick(v);});parent.addView(b,new LinearLayout.LayoutParams(-1,dp(50)));return b;}
    private void home(){
        filesScreen=false;body.removeAllViews();
        LinearLayout c=card();TextView heading=text("Komputery w sieci",21,INK);heading.setTypeface(null,Typeface.BOLD);c.addView(heading);
        c.addView(text("Otwieraj folder Windows i przesyłaj pliki w obie strony.",14,MUTED));
        button("Szukaj komputerów",c,v->discover());button("Dodaj komputer ręcznie",c,v->pair(null,null));
        for(SmbRepository.Credentials item:computers){
            gap();LinearLayout box=card();box.addView(text(item.host,20,INK));box.addView(text(item.share+"  ·  "+item.username,13,MUTED));
            button("Otwórz folder",box,v->connect(item,false));
            button("Usuń zapamiętane parowanie",box,v->new AlertDialog.Builder(this).setTitle("Usunąć parowanie?").setMessage("Pliki na komputerze pozostaną bez zmian.").setNegativeButton("Anuluj",null).setPositiveButton("Usuń",(d,w)->{try{List<SmbRepository.Credentials> updated=new ArrayList<>(computers);updated.remove(item);store.save(updated);if(active==item)disconnect();computers.clear();computers.addAll(updated);home();}catch(Exception e){showError("Nie zapisano zmiany parowania.");}}).show());
        }
        gap();LinearLayout phone=card();phone.addView(text("Folder telefonu na komputerze",21,INK));
        phone.addView(text("Wybierz folder telefonu. Komputer otworzy go w przeglądarce przez lokalny link.",14,MUTED));
        if(phoneServer!=null&&phoneServer.isRunning()){
            TextView link=text(phoneServer.getUrl(),15,TEAL);link.setTextIsSelectable(true);phone.addView(link);
            button("Kopiuj link dla komputera",phone,v->{ClipboardManager cb=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cb.setPrimaryClip(ClipData.newPlainText("Folder LAN",phoneServer.getUrl()));Toast.makeText(this,"Link skopiowany. Udostępnia dostęp do wybranego folderu.",Toast.LENGTH_LONG).show();});
            button("Zatrzymaj udostępnianie telefonu",phone,v->{phoneServer.stop();phoneServer=null;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);home();});
        }else button("Wybierz i udostępnij folder telefonu",phone,v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,SHARE_PHONE);});
        button("O aplikacji i licencje",body,v->{try(InputStream stream=getAssets().open("THIRD_PARTY_LICENSES.txt")){ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] bytes=new byte[4096];int count;while((count=stream.read(bytes))!=-1)output.write(bytes,0,count);showError("Folder LAN 1.0\n\n"+output.toString("UTF-8"));}catch(IOException e){showError("Folder LAN 1.0 — licencje w pakiecie źródłowym.");}});
        gap();body.addView(text("Bez konta i chmury. Dostęp SMB wymaga jednorazowego parowania. Udostępniaj tylko w zaufanej sieci lokalnej.",12,MUTED));
        button("Usuń wszystkie zapisane poświadczenia",body,v->new AlertDialog.Builder(this).setTitle("Usunąć wszystkie parowania?").setNegativeButton("Anuluj",null).setPositiveButton("Usuń",(d,w)->{store.clear();disconnect();computers.clear();home();}).show());
    }
    private android.net.Network wifiNetwork(){
        ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        android.net.Network activeNetwork=cm.getActiveNetwork();
        if(activeNetwork!=null){NetworkCapabilities caps=cm.getNetworkCapabilities(activeNetwork);if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return activeNetwork;}
        for(android.net.Network network:cm.getAllNetworks()){NetworkCapabilities caps=cm.getNetworkCapabilities(network);if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&cm.getLinkProperties(network)!=null)return network;}
        return null;
    }
    private boolean lanReady(){
        if(wifiNetwork()==null){showError("Brak połączenia Wi-Fi. Połącz telefon z tą samą siecią lokalną co komputer.");return false;}return true;
    }
    private void discover(){discover(true);}
    private void discover(boolean manual){
        if(!lanReady())return;
        task("Szukam komputerów w Wi-Fi…",()->new LanDiscovery(this).discover(), found->{
            if(found.isEmpty()){if(!manual){status.setText("Nie znaleziono komputerów. Możesz wpisać adres IP ręcznie.");return;}showError("Nie znaleziono komputerów. Sprawdź, czy komputer jest włączony, działa wykrywanie z PS1 i urządzenia są w tej samej sieci. Możesz wpisać IP ręcznie.");return;}
            for(LanDiscovery.Host host:found)if(host.source.contains("TCP47291"))legacyHosts.add(host.address);
            String[] names=new String[found.size()];for(int i=0;i<names.length;i++)names[i]=found.get(i).name+"\n"+found.get(i).address;
            new AlertDialog.Builder(this).setTitle("Znalezione komputery").setItems(names,(d,w)->{LanDiscovery.Host h=found.get(w);SmbRepository.Credentials saved=null;for(SmbRepository.Credentials c:computers)if(c.host.equalsIgnoreCase(h.address)&&c.share.equals(h.share)){saved=c;break;}if(saved==null)pair(h.address,h.share);else connect(saved,false);}).setNegativeButton("Zamknij",null).show();
        });
    }
    private EditText field(LinearLayout form,String hint,String value,boolean password){EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);e.setText(value);e.setTextColor(INK);e.setTextSize(16);e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);e.setInputType(password?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);form.addView(e,new LinearLayout.LayoutParams(-1,dp(55)));return e;}
    private void pair(String host,String share){
        LinearLayout f=new LinearLayout(this);f.setOrientation(LinearLayout.VERTICAL);f.setPadding(dp(22),dp(8),dp(22),0);
        f.addView(text("Wpisz poświadczenia ustawione na komputerze. Zapis nastąpi dopiero po udanym połączeniu; hasło będzie zaszyfrowane kluczem Android Keystore.",13,MUTED));
        EditText h=field(f,"Adres IP lub nazwa komputera",host==null?"":host,false);
        EditText s=field(f,"Nazwa udziału",share==null?"FolderUdostepnionyLAN":share,false);
        EditText u=field(f,"Użytkownik SMB","FolderLAN_SVC",false);
        EditText domain=field(f,"Domena / nazwa PC (opcjonalnie)","",false);
        EditText p=field(f,"Hasło z konfiguracji komputera","",true);
        ScrollView scroll=new ScrollView(this);scroll.addView(f);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Jednorazowe parowanie").setView(scroll).setNegativeButton("Anuluj",(d,w)->p.setText("")).setPositiveButton("Połącz i zapamiętaj",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v2->{
            if(h.getText().toString().trim().isEmpty()||u.getText().toString().trim().isEmpty()||p.getText().length()==0){p.setError("Podaj adres, użytkownika i hasło.");return;}
            SmbRepository.Credentials c;try{c=new SmbRepository.Credentials(h.getText().toString().trim(),s.getText().toString().trim(),u.getText().toString().trim(),domain.getText().toString().trim(),p.getText().toString());}catch(IllegalArgumentException error){h.setError("Sprawdź adres, nazwę udziału i użytkownika.");return;}
            p.setText("");dialog.dismiss();connect(c,true);
        }));dialog.show();
    }
    private void connect(SmbRepository.Credentials credentials,boolean remember){
        if(!lanReady())return;
        task("Łączę z "+credentials.host+"…",()->{
            if(legacyHosts.contains(credentials.host)){char[] pass=credentials.password.toCharArray();try{new LanDiscovery(this).verifyLegacyHost(credentials.host,pass);}finally{Arrays.fill(pass,'\0');}}
            if(repository!=null)repository.close();android.net.Network wifi=wifiNetwork();if(wifi==null)throw new IOException("Brak połączenia Wi-Fi.");
            java.net.InetAddress[] resolved=wifi.getAllByName(credentials.host);java.net.InetAddress chosen=null;
            for(java.net.InetAddress ip:resolved){if(!HostPolicy.isLocalAddress(ip))throw new IOException("Dozwolony jest wyłącznie lokalny adres LAN komputera.");if(chosen==null||ip.getAddress().length==4)chosen=ip;}
            if(chosen==null)throw new IOException("Nie znaleziono komputera.");
            repository=new SmbRepository(wifi.getSocketFactory());repository.connect(new SmbRepository.Credentials(chosen.getHostAddress(),credentials.share,credentials.username,credentials.domain,credentials.password));
            List<SmbRepository.Entry> entries=repository.list("");
            if(remember){List<SmbRepository.Credentials> updated=new ArrayList<>(computers);updated.removeIf(c->c.host.equalsIgnoreCase(credentials.host)&&c.share.equals(credentials.share));updated.add(credentials);store.save(updated);}
            return entries;
        }, entries->{active=credentials;path="";if(remember){computers.removeIf(c->c.host.equalsIgnoreCase(credentials.host)&&c.share.equals(credentials.share));computers.add(credentials);}renderFiles(entries);});
    }
    private void navigate(String next){task("Odczytuję folder…",()->repository.list(next),items->{path=next;renderFiles(items);});}
    private void renderFiles(List<SmbRepository.Entry> entries){
        filesScreen=true;body.removeAllViews();status.setText(getString(R.string.connected,active.host));
        LinearLayout top=card();top.addView(text(path.isEmpty()?active.share:active.share+" / "+path.replace('\\','/'),19,INK));
        button(path.isEmpty()?"← Komputery":"← Folder wyżej",top,v->{if(path.isEmpty())home();else navigate(SharePath.parent(path));});
        button("Wyślij plik z telefonu",top,v->{pendingUploadDirectory=path;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,UPLOAD);});
        button("Utwórz folder",top,v->input("Nowy folder","Nazwa folderu",name->{String newPath=SharePath.child(path,name);task("Tworzę folder…",()->{repository.mkdir(newPath);return repository.list(path);},this::renderFiles);}));
        button("Odśwież",top,v->navigate(path));
        if(entries.isEmpty()){gap();body.addView(text("Ten folder jest pusty.",17,MUTED));}
        for(SmbRepository.Entry entry:entries){
            gap();LinearLayout row=card();TextView label=text((entry.isDirectory?"▣  ":"□  ")+entry.name,18,INK);row.addView(label);
            if(!entry.isDirectory)row.addView(text(android.text.format.Formatter.formatFileSize(this,entry.size),12,MUTED));
            button(entry.isDirectory?"Otwórz folder":"Pobierz plik",row,v->{String selected=SharePath.child(path,entry.name);if(entry.isDirectory)navigate(selected);else{pendingDownload=selected;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/octet-stream");i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_TITLE,entry.name);startActivityForResult(i,DOWNLOAD);}});
            button("Zmień nazwę / Usuń",row,v->new AlertDialog.Builder(this).setTitle(entry.name).setItems(new String[]{"Zmień nazwę","Usuń"},(d,w)->{if(w==0)input("Zmień nazwę",entry.name,name->{String from=SharePath.child(path,entry.name),to=SharePath.child(path,name);task("Zmieniam nazwę…",()->{repository.rename(from,to);return repository.list(path);},this::renderFiles);});else confirmDelete(entry);}).show());
        }
    }
    private void confirmDelete(SmbRepository.Entry entry){new AlertDialog.Builder(this).setTitle("Usunąć: "+entry.name+"?").setMessage(entry.isDirectory?"Usuwane są wyłącznie puste foldery. Operacji nie można cofnąć.":"Plik zostanie usunięty z komputera. Operacji nie można cofnąć.").setNegativeButton("Anuluj",null).setPositiveButton("Usuń",(d,w)->{String selected=SharePath.child(path,entry.name);task("Usuwam…",()->{repository.delete(selected,entry.isDirectory);return repository.list(path);},this::renderFiles);}).show();}
    private interface NameAction{void run(String name);}
    private void input(String title,String hint,NameAction action){EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);e.setPadding(dp(22),dp(12),dp(22),dp(12));AlertDialog d=new AlertDialog.Builder(this).setTitle(title).setView(e).setNegativeButton("Anuluj",null).setPositiveButton("Zapisz",null).create();d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{String name=e.getText().toString();SharePath.validateName(name);d.dismiss();action.run(name);}catch(IllegalArgumentException ex){e.setError("Podaj nazwę bez ukośników, .. i znaków zastrzeżonych.");}}));d.show();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        if((request==UPLOAD||request==DOWNLOAD)&&(repository==null||!repository.isConnected()||(request==UPLOAD&&pendingUploadDirectory==null)||(request==DOWNLOAD&&pendingDownload==null))){if(request==DOWNLOAD)try{DocumentsContract.deleteDocument(getContentResolver(),uri);}catch(Exception ignored){}showError("Połączenie zostało zamknięte. Otwórz ponownie folder komputera i wybierz plik jeszcze raz.");return;}
        if(request==UPLOAD){String name=displayName(uri);String destination;try{destination=SharePath.child(pendingUploadDirectory,name);}catch(Exception e){showError("Niepoprawna nazwa pliku.");return;}task("Wysyłam "+name+"…",()->{try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException();repository.upload(destination,in);}return repository.list(path);},items->{renderFiles(items);Toast.makeText(this,"Plik wysłany. Istniejące pliki nie są nadpisywane.",Toast.LENGTH_LONG).show();});}
        if(request==DOWNLOAD){String source=pendingDownload;task("Pobieram plik…",()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"w")){if(out==null)throw new IOException();repository.download(source,out);}catch(Exception error){try{DocumentsContract.deleteDocument(getContentResolver(),uri);}catch(Exception ignored){}throw error;}return true;},ok->Toast.makeText(this,"Plik zapisany w wybranym miejscu.",Toast.LENGTH_LONG).show());}
        if(request==SHARE_PHONE){
            if(!lanReady())return;
            try{if((data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0)getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);else getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException e){showError("Nie otrzymano trwałego dostępu do folderu. Wybierz inny folder.");return;}
            new AlertDialog.Builder(this).setTitle("Udostępnić folder telefonu?").setMessage("Każdy, komu przekażesz lokalny link, otrzyma dostęp do wybranego folderu. Połączenie przeglądarki używa HTTP bez szyfrowania — korzystaj tylko z zaufanego Wi-Fi. Zatrzymaj udostępnianie po zakończeniu i pozostaw aplikację otwartą.").setNegativeButton("Anuluj",null).setPositiveButton("Udostępnij",(d,w)->task("Uruchamiam folder telefonu…",()->{if(phoneServer!=null)phoneServer.stop();phoneServer=new PhoneFolderServer(this,uri);phoneServer.start();return true;},ok->{getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);home();})).show();
        }
    }
    private String displayName(Uri uri){try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}return "plik";}
    private interface Work<T>{T run()throws Exception;}
    private interface Done<T>{void run(T value);}
    private <T> void task(String message,Work<T> work,Done<T> done){if(busy)return;busy=true;status.setText(message);progress.setVisibility(View.VISIBLE);io.execute(()->{try{T result=work.run();runOnUiThread(()->{if(destroyed)return;busy=false;progress.setVisibility(View.GONE);status.setText(R.string.ready);done.run(result);});}catch(Exception e){runOnUiThread(()->{if(destroyed)return;busy=false;progress.setVisibility(View.GONE);status.setText(R.string.failed);showError(errorMessage(e));});}});}
    private String errorMessage(Exception e){String safe=e.getMessage();if(safe!=null)for(String prefix:new String[]{"Błędne poświadczenia","Komputer odmówił","Nie znaleziono udziału","Plik lub folder","Folder nie jest pusty","Dozwolony jest wyłącznie","Nie znaleziono komputera","Brak połączenia","Ze względów bezpieczeństwa","Komputer nie odpowiada","Nie udało się połączyć","Nie można"})if(safe.startsWith(prefix))return safe;String type=(e.getClass().getSimpleName()+" "+String.valueOf(e.getMessage())).toLowerCase(Locale.ROOT);if(type.contains("logon")||type.contains("auth")||type.contains("password")||type.contains("access_denied")||type.contains("poświadczen")||type.contains("potwierdzenia"))return "Brak dostępu. Sprawdź użytkownika, hasło SMB i prawa do folderu. W razie zmiany hasła usuń parowanie i dodaj komputer ponownie.";if(type.contains("publicz")||type.contains("lokaln"))return "Podaj lokalny adres komputera w tej samej sieci Wi-Fi. Publiczne adresy internetowe są blokowane.";if(type.contains("exists")||type.contains("collision")||type.contains("już istnieje"))return "Ta nazwa już istnieje. Zmień nazwę pliku lub folderu; dane nie zostały nadpisane.";if(type.contains("not_empty")||type.contains("not empty")||type.contains("nie jest pusty"))return "Folder zawiera pliki. Najpierw usuń lub przenieś zawartość.";if(type.contains("timeout")||type.contains("connect")||type.contains("unknownhost")||type.contains("unreachable"))return "Komputer nie odpowiada. Sprawdź jego zasilanie, adres, tę samą sieć Wi-Fi i dostępność SMB (port 445).";return "Nie udało się wykonać operacji. Sprawdź połączenie LAN, nazwę udziału i uprawnienia. Wymagany jest SMB3 z podpisywaniem i szyfrowaniem; starsze udziały mogą być niezgodne.";}
    private void disconnect(){active=null;io.execute(()->{try{if(repository!=null)repository.close();}catch(IOException ignored){}});}
    private void showError(String message){if(!isFinishing())new AlertDialog.Builder(this).setTitle("Folder LAN").setMessage(message).setPositiveButton("Rozumiem",null).show();}
    @Override public void onBackPressed(){if(busy)return;if(filesScreen){if(path.isEmpty())home();else navigate(SharePath.parent(path));}else super.onBackPressed();}
    @Override protected void onStop(){if(phoneServer!=null){phoneServer.stop();phoneServer=null;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}super.onStop();}
    @Override protected void onResume(){super.onResume();if(body!=null&&!filesScreen&&!busy)home();}
    @Override protected void onDestroy(){destroyed=true;if(phoneServer!=null)phoneServer.stop();io.execute(()->{try{if(repository!=null)repository.close();}catch(IOException ignored){}});io.shutdown();super.onDestroy();}
}
