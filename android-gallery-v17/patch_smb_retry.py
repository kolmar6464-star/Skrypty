from pathlib import Path

p=Path("app/src/main/java/pl/folderlan/android/LanShare.java")
s=p.read_text(encoding="utf-8")
def edit(old,new):
    global s
    assert s.count(old)==1, "Expected one LanShare failover anchor: "+old[:110]
    s=s.replace(old,new,1)

edit('    private Host host;\n','''    private Host host;
    // A peer can still answer signed UDP while its SMB service is offline.
    // Remember recent failed SMB IDs across reconnection retries, with expiry.
    private final java.util.HashSet<String> rejectedHosts=new java.util.HashSet<>();
''')
edit('''                if (candidate == null) continue;
                // More than one authenticated SMB host can be visible during''',
'''                if (candidate == null || rejectedHosts.contains(candidate.id)) continue;
                // More than one authenticated SMB host can be visible during''')
edit('''    void connect(Context ctx) throws Exception {
        Exception previous=null;''',
'''    private void loadRejected(Context ctx) {
        android.content.SharedPreferences prefs=ctx.getSharedPreferences(
            "folderlan_smb_failover",Context.MODE_PRIVATE);
        String data=prefs.getString("failed","");
        long date=prefs.getLong("date",0);
        if(System.currentTimeMillis()-date>90000) {
            prefs.edit().clear().apply();
            return;
        }
        for(String id:data.split(","))if(id.matches("[0-9a-f]{32}"))rejectedHosts.add(id);
    }
    private void reject(Context ctx,String id) {
        if(id==null || !id.matches("[0-9a-f]{32}"))return;
        rejectedHosts.add(id);
        StringBuilder list=new StringBuilder();
        for(String bad:rejectedHosts) {
            if(list.length()>0)list.append(",");
            list.append(bad);
        }
        ctx.getSharedPreferences("folderlan_smb_failover",Context.MODE_PRIVATE)
            .edit().putString("failed",list.toString())
            .putLong("date",System.currentTimeMillis()).apply();
    }

    void connect(Context ctx) throws Exception {
        loadRejected(ctx);
        Exception previous=null;''')
edit('''                // Purge stale identity and re-discover using the existing pairing key.
                forgetOutdatedHost(ctx);''',
'''                // Purge stale identity and re-discover using the existing pairing key.
                reject(ctx,recent.id);
                forgetOutdatedHost(ctx);''')
edit('''        try {
            Host discovered=discover(ctx);
            connectKnown(discovered);
            rememberVerified(ctx,discovered);
        } catch (Exception e) {
            if (previous != null)
                throw new Exception(e.getMessage()+" Ostatni komputer również nie odpowiada: "
                    +previous.getClass().getSimpleName(),e);
            throw e;
        }
    }

    private void connectKnown''',
'''        // If any discovered host fails SMB authentication/connection, DO NOT
        // repeatedly select it. Try a different signed candidate, without
        // allowing unverified IPs or changing the shared pairing secret.
        for(int attempt=0;attempt<6;attempt++) {
            Host discovered=null;
            try {
                discovered=discover(ctx);
                connectKnown(discovered);
                rememberVerified(ctx,discovered);
                return;
            } catch (Exception failure) {
                previous=failure;
                if(discovered==null)break;
                reject(ctx,discovered.id);
                close();
            }
        }
        throw new Exception("Żaden uwierzytelniony komputer SMB nie jest dostępny. " +
            (previous==null?"":previous.getMessage()),previous);
    }

    private void connectKnown''')
p.write_text(s,encoding="utf-8")
print("Verified failover: signed candidates only, temporary rejection of failing SMB host, retry remaining LAN peers.")
