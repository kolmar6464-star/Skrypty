package pl.asuscontrol.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Locale;

/**
 * ASUS Control PL v0.6. Bez VPN.
 * Zdalnie działa tylko wtedy, gdy router JUŻ ma dostępny bezpieczny HTTPS WAN.
 * Nie włącza automatycznie Web Access from WAN ani nie próbuje omijać CGNAT.
 */
public class MainActivity extends Activity {
    private static final int BG = Color.rgb(19,21,26), CARD = Color.rgb(34,37,44),
            WHITE = Color.WHITE, MUTED = Color.rgb(186,194,204), RED = Color.rgb(196,32,43);
    private LinearLayout root, advanced;
    private TextView state, detectedLabel;
    private EditText login, password, localIpInput, remoteUrlInput;
    private CheckBox savePasswordCheck;
    private CredentialVault credentialVault;
    private String discoveredGateway;
    private WebView browser;
    private String approvedHost;
    private boolean remoteMode, usedAuth, usedForm, active;
    private String user, secret;
    private SharedPreferences preferences;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        preferences = getSharedPreferences("router", MODE_PRIVATE);
        credentialVault = new CredentialVault(this);
        showLogin();
    }
    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private TextView label(String value,int size,int color){
        TextView t=new TextView(this);
        t.setText(value); t.setTextColor(color); t.setTextSize(size);
        t.setPadding(0,dp(8),0,dp(7)); return t;
    }
    private EditText field(String hint,String value,int flags){
        EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);e.setText(value);
        e.setInputType(flags);e.setTextColor(WHITE);e.setHintTextColor(MUTED);
        e.setBackgroundTintList(android.content.res.ColorStateList.valueOf(RED));
        e.setPadding(dp(8),dp(8),dp(8),dp(8));return e;
    }
    private Button btn(String text, Runnable action) {
        Button b=new Button(this); b.setText(text);b.setTextColor(WHITE);b.setAllCaps(false);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(RED));
        b.setOnClickListener(v->action.run());return b;
    }
    private LinearLayout column(){
        LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(20),dp(12),dp(20),dp(18));return v;
    }
    private void showLogin(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        root=column();scroll.addView(root);
        root.addView(label("ASUS CONTROL PL",27,RED));
        root.addView(label("RT-AC1200G+  •  bez VPN  •  Android",14,MUTED));
        discoveredGateway=wifiGateway();
        String saved=preferences.getString("local","192.168.2.1");
        if(discoveredGateway!=null){
            detectedLabel=label("Brama Wi-Fi: "+discoveredGateway+" (wymaga potwierdzenia)",14,MUTED);
        } else {
            detectedLabel=label("Brak domowej bramy Wi-Fi. Możliwy tryb zdalny HTTPS.",14,MUTED);
        }
        root.addView(detectedLabel);
        root.addView(label("Login administratora ASUS",15,WHITE));
        login=field("Login","",InputType.TYPE_CLASS_TEXT);
        root.addView(login);
        root.addView(label("Hasło administratora ASUS",15,WHITE));
        password=field("Hasło","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(password);
        root.addView(btn("POŁĄCZ Z ROUTEREM",()->connect()));
        state=label("W sieci domowej: wykrywanie automatyczne. Poza domem: wymagany wcześniej skonfigurowany adres HTTPS/DDNS oraz publiczny adres WAN.",13,MUTED);
        root.addView(state);
        root.addView(btn("ADRES ROUTERA / USTAWIENIA",()->advanced.setVisibility(advanced.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE)));
        advanced=column();advanced.setBackgroundColor(CARD);advanced.setVisibility(View.GONE);
        root.addView(advanced);
        advanced.addView(label("Adres lokalny (domyślnie 192.168.2.1)",14,WHITE));
        localIpInput=field("192.168.2.1",saved,InputType.TYPE_CLASS_TEXT);
        advanced.addView(localIpInput);
        advanced.addView(btn("USTAW LOKALNY ADRES NA WYKRYTY",()->{
            if(discoveredGateway!=null)localIpInput.setText(discoveredGateway);
            else state.setText("Nie wykryto bramy aktualnej sieci Wi-Fi.");
        }));
        advanced.addView(label("Zdalny adres HTTPS (opcjonalny, np. https://mojrouter.asuscomm.com:8443)",14,WHITE));
        remoteUrlInput=field("https://nazwa.asuscomm.com:8443",preferences.getString("remote",""),
                InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        advanced.addView(remoteUrlInput);
        advanced.addView(btn("ZAPISZ ADRESY",()->saveAddresses()));
        advanced.addView(label("Nie włączam publicznego panelu WAN, nie konfiguruję DDNS na routerze i nie omijam CGNAT. Bez tego dostęp z 4G/5G może nie działać. Zapis hasła jest opcjonalny i szyfrowany.",13,MUTED));
        setContentView(scroll);
    }
    private String wifiGateway(){
        ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null)return null;
        try{
            for(Network n:cm.getAllNetworks()){
                NetworkCapabilities caps=cm.getNetworkCapabilities(n);
                if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue;
                LinkProperties lp=cm.getLinkProperties(n);
                if(lp==null)continue;
                for(RouteInfo route:lp.getRoutes()){
                    InetAddress gateway=route.getGateway();
                    if(route.isDefaultRoute()&&gateway instanceof Inet4Address)return gateway.getHostAddress();
                }
            }
        }catch(Exception ignored){}
        return null;
    }
    private static boolean validPrivateIp(String ip){
        if(ip==null)return false;
        String[] a=ip.trim().split("\\.",-1);
        if(a.length!=4)return false;
        int[] n=new int[4];
        try {
            for(int i=0;i<4;i++){if(a[i].isEmpty()||a[i].length()>3)return false;
                n[i]=Integer.parseInt(a[i]);if(n[i]<0||n[i]>255)return false;}
        }catch(NumberFormatException e){return false;}
        return n[0]==10 || (n[0]==172&&n[1]>=16&&n[1]<=31)
                ||(n[0]==192&&n[1]==168);
    }
    private static Uri checkedRemote(String s){
        if(s==null||s.trim().isEmpty())return null;
        try{
            Uri u=Uri.parse(s.trim());
            if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null
                    ||u.getHost().isEmpty()||u.getUserInfo()!=null||u.getFragment()!=null
                    ||u.getQuery()!=null)return null;
            if(!u.getPath().isEmpty()&&!"/".equals(u.getPath()))return null;
            if(u.getPort()!=-1 && (u.getPort()<1024||u.getPort()>65535))return null;
            return u;
        }catch(Exception e){return null;}
    }
    private void saveAddresses(){
        String ip=localIpInput.getText().toString().trim(),url=remoteUrlInput.getText().toString().trim();
        if(!validPrivateIp(ip)){state.setText("Błędny prywatny adres IP. Przykład: 192.168.2.1");return;}
        if(!url.isEmpty()&&checkedRemote(url)==null){
            state.setText("Zdalny adres musi być HTTPS, np. https://mojrouter.asuscomm.com:8443");return;}
        preferences.edit().putString("local",ip).putString("remote",url).apply();
        state.setText("Zapisano adresy routera. Opcja zapamiętania hasła jest osobnym checkboxem.");
        advanced.setVisibility(View.GONE);
    }
    private void connect(){
        user=login.getText().toString().trim();secret=password.getText().toString();
        if(user.isEmpty()||secret.isEmpty()){state.setText("Wpisz login i hasło ASUS.");return;}
        String savedIp=preferences.getString("local","192.168.2.1");
        // Automatyczne wykrywanie bramy nie oznacza, że to ASUS – wymagamy potwierdzenia.
        boolean ownWifi=discoveredGateway!=null && discoveredGateway.equals(savedIp);
        final String endpoint;
        if(ownWifi){
            remoteMode=false;endpoint="http://"+savedIp+"/";
        } else {
            Uri remote=checkedRemote(preferences.getString("remote",""));
            if(remote==null) {
                state.setText(discoveredGateway==null?
                    "Nie jesteś w domowej sieci. Aby użyć 4G/5G bez VPN, router musi wcześniej udostępniać HTTPS WAN oraz DDNS. Ustaw adres w sekcji ADRES ROUTERA.":
                    "Obecna brama Wi-Fi jest inna niż zapisana. Nie wysyłam hasła na obcy router. Ustaw własny IP lub adres zdalny HTTPS.");
                advanced.setVisibility(View.VISIBLE);return;
            }
            remoteMode=true;endpoint=remote.toString();
        }
        String mode=remoteMode?"INTERNET MOBILNY / HTTPS":"DOMOWE WI-FI / HTTP";
        String warning=remoteMode?
           "Zdalny dostęp wymaga już aktywnej usługi HTTPS na ASUS i publicznego WAN IP. Stary firmware może być niebezpieczny. Połączenie zostanie przerwane przy błędzie certyfikatu.":
           "Wykrycie adresu bramy nie potwierdza producenta routera. Potwierdź, że to Twoje domowe Wi-Fi. W trybie lokalnym HTTP nie szyfruje hasła.";
        new AlertDialog.Builder(this).setTitle("Potwierdź router: "+mode)
            .setMessage("Adres: "+endpoint+"\n\n"+warning)
            .setNegativeButton("ANULUJ",(d,w)->{secret=null;user=null;})
            .setPositiveButton("TO MÓJ ROUTER",(d,w)->showPanel(endpoint))
            .show();
    }
    private void showPanel(String endpoint){
        Uri uri=Uri.parse(endpoint);
        approvedHost=uri.getHost().toLowerCase(Locale.ROOT);
        active=true;usedAuth=false;usedForm=false;
        root.removeAllViews();
        if (root.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup)root.getParent()).removeView(root);
        root.setPadding(dp(10),dp(10),dp(10),dp(10));
        root.addView(label((remoteMode?"ZDALNIE HTTPS: ":"LOKALNIE: ")+approvedHost,15,RED));
        root.addView(btn("WYLOGUJ I ZAMKNIJ",()->{closePanel();showLogin();}));
        state=label("Łączenie z routerem…",13,MUTED);root.addView(state);
        browser=new WebView(this);
        browser.getSettings().setJavaScriptEnabled(true);
        browser.getSettings().setDomStorageEnabled(true);
        browser.getSettings().setAllowFileAccess(false);
        browser.getSettings().setAllowContentAccess(false);
        browser.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        browser.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        browser.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser,false);
        browser.setWebViewClient(new WebViewClient(){
            private boolean allowed(Uri u){
                if(!active||u==null||u.getHost()==null||u.getScheme()==null)return false;
                String proto=remoteMode?"https":"http";
                return approvedHost.equalsIgnoreCase(u.getHost())&&proto.equalsIgnoreCase(u.getScheme())
                        && (u.getPort()==uri.getPort()||u.getPort()==-1&&uri.getPort()==-1);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest req){
                if(!allowed(req.getUrl())){state.setText("Zablokowano przekierowanie na obcy adres.");return true;}
                return false;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v,String url){
                if(!allowed(Uri.parse(url))){state.setText("Zablokowano obcy adres.");return true;}return false;
            }
            @Override public void onReceivedSslError(WebView v,SslErrorHandler handler,SslError error){
                handler.cancel();secret=null;
                state.setText("BŁĄD CERTYFIKATU HTTPS. Ze względów bezpieczeństwa połączenie odrzucono.");
            }
            @Override public void onReceivedHttpAuthRequest(WebView v,HttpAuthHandler handler,String host,String realm){
                if(!active||!approvedHost.equalsIgnoreCase(host)||usedAuth||secret==null){handler.cancel();return;}
                usedAuth=true;handler.proceed(user,secret);secret=null;
                state.setText("Wysłano login przez mechanizm uwierzytelniania routera.");
            }
            @Override public void onPageFinished(WebView v,String page){
                if(!active||!allowed(Uri.parse(page)))return;
                state.setText("Panel ASUS. Jeśli automatyczne logowanie nie zadziała, zaloguj się w panelu.");
                if(usedForm||usedAuth||secret==null)return;
                // Próbujemy tylko znanych pól formularza ASUSWRT, na uprzednio zatwierdzonym hoście.
                if(!(page.contains("login")||page.endsWith("/")))return;
                String js="(function(){var u=document.querySelector('#login_username,input[name=login_username],input[name=username]');"+
                    "var p=document.querySelector('#login_passwd,input[name=login_passwd],input[type=password]');"+
                    "if(!u||!p)return 'brak';u.value="+JSONObject.quote(user)+";p.value="+JSONObject.quote(secret)+";"+
                    "if(typeof window.login==='function'){window.login();return 'wyslano';}"+
                    "var f=p.form;if(f){var b=f.querySelector('button[type=submit],input[type=submit]');if(b){b.click();return 'wyslano';}}"+
                    "return 'wpisano';})()";
                usedForm=true;secret=null; // dziennik i preferencje nigdy nie otrzymują hasła
                v.evaluateJavascript(js, result->{
                    if("\"wyslano\"".equals(result))state.setText("Próba logowania do ASUS została wysłana.");
                    else state.setText("Panel otwarty. Jeżeli wymaga danych, zaloguj się bezpośrednio w panelu.");
                });
            }
            @Override public void onReceivedError(WebView v,android.webkit.WebResourceRequest r,android.webkit.WebResourceError e){
                if(r.isForMainFrame())state.setText("Nie można otworzyć routera. Sprawdź adres, publiczny WAN, DDNS i certyfikat HTTPS.");
            }
        });
        root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
        // ScrollView nie nadaje dzieciom prawidłowego rozmiaru dla weight=1: osobny kontener
        LinearLayout shell=column();shell.setBackgroundColor(BG);
        shell.addView(root,new LinearLayout.LayoutParams(-1,-1));
        setContentView(shell);
        browser.loadUrl(endpoint);
    }
    private void closePanel(){
        active=false;secret=null;user=null;
        if(browser!=null){
            try{browser.stopLoading();browser.clearHistory();browser.clearCache(true);
                android.webkit.WebViewDatabase.getInstance(MainActivity.this).clearHttpAuthUsernamePassword();browser.loadUrl("about:blank");
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();browser.destroy();
            }catch(Exception ignored){}
            browser=null;
        }
    }
    @Override public void onBackPressed(){
        if(active){closePanel();showLogin();}else super.onBackPressed();
    }
    @Override protected void onDestroy(){closePanel();super.onDestroy();}
}
