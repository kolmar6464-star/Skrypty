package pl.folderlan.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhoneServerSecurityTest {
    @Test public void tokenHas128BitsAndChanges(){String a=PhoneServerSecurity.token(),b=PhoneServerSecurity.token();assertTrue(a.matches("[0-9a-f]{32}"));assertNotEquals(a,b);}
    @Test public void tokenCannotMatchPrefixOrDescendant(){String token=PhoneServerSecurity.token();assertTrue(PhoneServerSecurity.tokenPath("/"+token+"/",token));assertFalse(PhoneServerSecurity.tokenPath("/"+token+"/other",token));assertFalse(PhoneServerSecurity.tokenPath("/",token));}
    @Test public void pathPreservesUnicode(){assertEquals("Zdjęcia/nowe zdjęcie.jpg",PhoneServerSecurity.normalize("Zdjęcia/nowe zdjęcie.jpg"));}
    @Test public void rootIsAllowed(){assertEquals("",PhoneServerSecurity.normalize(""));}
    @Test public void traversalAndAmbiguitiesRejected(){for(String value:new String[]{"../x","folder/../x","a//b","/root","folder/","a\\b","a/./b","a/\u0000b"}){try{PhoneServerSecurity.normalize(value);fail(value);}catch(IllegalArgumentException expected){}}}
    @Test public void rootCannotBeUsedAsName(){for(String value:new String[]{"",".","..","a/b","a\\b","bad\nname"}){try{PhoneServerSecurity.name(value);fail(value);}catch(IllegalArgumentException expected){}}}
    @Test public void htmlCannotInjectAttributesOrTags(){assertEquals("&lt;script&gt;&quot;&#39;&amp;",PhoneServerSecurity.html("<script>\"'&"));}
    @Test public void originMustMatchExactly(){assertTrue(PhoneServerSecurity.sameOrigin("http://192.168.1.1:12","http://192.168.1.1:12"));assertFalse(PhoneServerSecurity.sameOrigin(null,"http://192.168.1.1:12"));assertFalse(PhoneServerSecurity.sameOrigin("http://192.168.1.1:12.evil","http://192.168.1.1:12"));}
    @Test public void linksUsePercentEncoding(){assertEquals("a%20b%26c",PhoneServerSecurity.encode("a b&c"));}
    @Test public void joinAndParentStayInTree(){assertEquals("a/b",PhoneServerSecurity.child("a","b"));assertEquals("a",PhoneServerSecurity.parent("a/b"));assertEquals("",PhoneServerSecurity.parent("a"));}
}
