package pl.folderlan.app;

/** Windows-relative paths only; all operations stay below the selected SMB share. */
public final class SharePath {
    private SharePath() { }

    public static String normalize(String path) {
        if (path == null) throw new IllegalArgumentException("Nie podano ścieżki.");
        if (path.isEmpty()) return "";
        if (path.length() > 4096 || path.startsWith("/") || path.startsWith("\\")) {
            throw new IllegalArgumentException("Ścieżka musi być względna wobec udziału.");
        }
        String[] components = path.split("[/\\\\]", -1);
        StringBuilder result = new StringBuilder();
        for (String component : components) {
            validateName(component);
            if (result.length() > 0) result.append('\\');
            result.append(component);
        }
        return result.toString();
    }

    public static String requireChild(String path) {
        String result = normalize(path);
        if (result.isEmpty()) throw new IllegalArgumentException("Nie można zmieniać głównego folderu udziału.");
        return result;
    }

    public static String child(String parent, String name) {
        validateName(name);
        String base = normalize(parent);
        return base.isEmpty() ? name : base + "\\" + name;
    }

    public static String parent(String path) {
        String normalized = normalize(path);
        int slash = normalized.lastIndexOf('\\');
        return slash < 0 ? "" : normalized.substring(0, slash);
    }

    public static void validateName(String name) {
        if (name == null || name.isEmpty() || name.length() > 255 || name.equals(".") || name.equals("..")
                || name.endsWith(".") || name.endsWith(" ")) {
            throw new IllegalArgumentException("Nieprawidłowa nazwa pliku lub folderu.");
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch < 32 || "\\/:*?\"<>|".indexOf(ch) >= 0) {
                throw new IllegalArgumentException("Nazwa zawiera znak niedozwolony w Windows.");
            }
        }
        String stem = name.split("\\.", 2)[0].toUpperCase(java.util.Locale.ROOT);
        if (stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
            throw new IllegalArgumentException("Ta nazwa jest zastrzeżona w Windows.");
        }
    }
}
