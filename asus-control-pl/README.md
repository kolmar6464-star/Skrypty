# ASUS Control PL v0.6 – bez VPN (wersja testowa)
Android 9+, model ASUS RT-AC1200G+. Adres lokalny domyślnie 192.168.2.1.
- W domu: odczytuje bramę Wi-Fi. Automatyczny wybór adresu działa po zatwierdzeniu.
- Poza domem: może otworzyć zapisany adres HTTPS (np. https://nazwa.asuscomm.com:8443) przy działającym zdalnym serwisie routera.
- Domyślnie NIE WŁĄCZA dostępu administracyjnego WAN, NIE ustawia DDNS, NIE omija CGNAT, nie ignoruje błędów certyfikatu.
- Login i hasło są wymagane przy połączeniu; hasło nie jest zapisywane w preferencjach.
- Aplikacja próbuje automatycznego logowania do panelu ASUS; zgodność zależy od wersji ASUSWRT.
## Ważne
To NIE jest aplikacja gwarantująca zdalny dostęp przy samym loginie i haśle. Zdalny dostęp HTTPS musi już działać i wymaga publicznego WAN IP. Włączenie panelu WAN na EOL RT-AC1200G+ stwarza zagrożenie.
Po skompilowaniu sprawdź działanie na fizycznym routerze i telefonie.