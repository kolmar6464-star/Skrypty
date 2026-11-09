package pl.folderlan.app;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Pure helpers shared by the HTTP adapter and local tests. */
final class PhoneServerSecurity {
    private PhoneServerSecurity() { }
    static String token() {
        byte[] bytes = new byte[16]; new SecureRandom().nextBytes(bytes);
        StringBuilder text = new StringBuilder(32);
        for (byte value : bytes) text.append(Character.forDigit((value >>> 4) & 15,16)).append(Character.forDigit(value & 15,16));
        return text.toString();
    }
    static boolean tokenPath(String uri, String token) {
        if (uri == null || token == null) return false;
        return MessageDigest.isEqual(uri.getBytes(StandardCharsets.UTF_8), ("/"+token+"/").getBytes(StandardCharsets.UTF_8));
    }
    static String normalize(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.length() > 2048 || value.startsWith("/") || value.endsWith("/")) throw new IllegalArgumentException("Nieprawidłowa ścieżka.");
        String[] parts = value.split("/",-1);
        for (String part:parts) name(part);
        return String.join("/",parts);
    }
    static String name(String value) {
        if (value==null || value.isEmpty() || value.length()>240 || value.equals(".") || value.equals("..")) throw new IllegalArgumentException("Nieprawidłowa nazwa.");
        for(int i=0;i<value.length();i++) if(Character.isISOControl(value.charAt(i)) || "\\/:*?\"<>|".indexOf(value.charAt(i))>=0) throw new IllegalArgumentException("Nazwa zawiera niedozwolone znaki.");
        return value;
    }
    static String child(String parent,String name) { return parent.isEmpty()?name(name):normalize(parent)+"/"+name(name); }
    static String parent(String path) { int i=path.lastIndexOf('/');return i<0?"":path.substring(0,i); }
    static String html(String text) {
        if(text==null)return "";
        return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
    }
    static String encode(String value) {try{return URLEncoder.encode(value,"UTF-8").replace("+","%20");}catch(java.io.UnsupportedEncodingException impossible){throw new IllegalStateException(impossible);}}
    static boolean sameOrigin(String header,String origin) {return origin!=null && origin.equals(header);}
}
