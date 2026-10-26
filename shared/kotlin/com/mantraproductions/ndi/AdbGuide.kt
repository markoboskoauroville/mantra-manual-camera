package com.mantraproductions.ndi

/** v150: the guide (Marko: "in settings, there must be a guide on how to set up ADB wireless. Then these two phones are
 *  talking through Wi-Fi, and they are able to control each other"). The same words are in the camera's settings. */
const val ADB_GUIDE = """Both phones on the same Wi-Fi.

ON THE CAMERA PHONE (once):
1. Settings → About phone → tap Build number seven times (Developer options appear).
2. Settings → System → Developer options → Wireless debugging: ON (allow it on this network).
3. Tap "Pair device with pairing code". It shows an address with a port (192.168.1.101:37123) and six digits. Keep that screen open.

ON THIS MONITOR:
4. Sources (double tap in the middle) → PAIR: the address, the port after the colon, the six digits → PAIR.
5. The camera phone now lists "Mantra Monitor" under Paired devices. Close its pairing screen.
6. In the sources, ADB ON, then choose "ADB 192.168.1.101". The camera's screen comes over Wi-Fi and every touch goes back.

Pairing is once. Wireless debugging turns itself off when the camera phone leaves the Wi-Fi or restarts: switch it on again (step 2), no new pairing. For the camera to drive this monitor the other way, do the same with the phones swapped."""
