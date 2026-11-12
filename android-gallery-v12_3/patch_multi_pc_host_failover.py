from pathlib import Path

# Based on the last approved Android v12.2, NOT v15/v16. Only peer selection
# and SMB reconnection change; gallery, alarms, controls remain untouched.
p=Path("app/src/main/java/pl/folderlan/android/LanShare.java")
s=p.read_text(encoding="utf-8")
def replace(old,new):
    global s
    n=s.count(old)
    if n!=1: raise RuntimeError("LanShare topology anchor count %d: %s"%(n,old[:110]))
    s=s.replace(old,new,1)

replace("""        Host found = null;
        byte[] received = new byte[512];""",
"""        java.util.TreeMap<String,Host> found = new java.util.TreeMap<>();
        byte[] received = new byte[512];""")
replace("""                if (found != null && !found.id.equals(candidate.id))
                    throw new Exception("Wykryto kilka komputerów głównych. Wybierz właściwy komputer.");
                found = candidate;
                // Valid signature and host identity are stronger than port probing.
                return found;
            } catch (java.net.SocketTimeoutException ignored) {}
        }
        return found;
    }""",
"""                // More than one authenticated SMB host can be visible during
                // handover. Rank by immutable GUID; all peers make same choice.
                found.put(candidate.id,candidate);
            } catch (java.net.SocketTimeoutException ignored) {}
        }
        return found.isEmpty() ? null : found.firstEntry().getValue();
    }""")
replace("""            for (int i=0;i<3;i++) {
                for (String address : broadcasts) {""",
"""            Host best=null;
            for (int i=0;i<3;i++) {
                for (String address : broadcasts) {""")
replace("""                Host host=collectReplies(socket,nonce.toString(),450);
                if (host != null) return host;
            }
            // Android broadcast delivery""",
"""                Host host=collectReplies(socket,nonce.toString(),450);
                if(host!=null && (best==null || host.id.compareTo(best.id)<0)) best=host;
            }
            if(best!=null) return best;
            // Android broadcast delivery""")
# Ensure next rescan always uses verified discovery and can discard stale host data.
assert 'forgetOutdatedHost(ctx)' in s
assert 'rememberVerified(ctx,discovered)' in s
assert 'isSameServer(Host latest)' in s
p.write_text(s,encoding="utf-8")
print("Only LanShare changed: authenticated deterministic host choice on takeover; old host cache and SMB reconnect retained.")
