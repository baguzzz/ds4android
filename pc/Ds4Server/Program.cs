// Ds4Server - PC companion untuk DS4Remote Android app
// Membuat virtual DualShock 4 via ViGEmBus, menerima input dari HP via TCP.
//
// PRASYARAT: install ViGEmBus driver dulu:
//   https://github.com/nefarius/ViGEmBus/releases
//   (pilih ViGEmBus_Setup_x64.msi, install, restart Windows)
//
// Build: dotnet publish -c Release
// Hasil: Ds4Server.exe (self-contained, tidak butuh .NET runtime)

using System.Net;
using System.Net.Sockets;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.DualShock4;

const int PORT = 27020;
const byte SYNC = 0xAA;
const int PACKET_SIZE = 12;

Console.WriteLine("==============================================");
Console.WriteLine("  Ds4Server - Virtual DualShock 4 untuk PC");
Console.WriteLine("==============================================");

// Cek ViGEmBus driver
ViGEmClient client;
try
{
    client = new ViGEmClient();
}
catch (Exception ex)
{
    Console.WriteLine("[X] ViGEmBus driver tidak terpasang.");
    Console.WriteLine("    Download: https://github.com/nefarius/ViGEmBus/releases");
    Console.WriteLine("    Detail error: " + ex.Message);
    Console.WriteLine("Tekan Enter untuk keluar...");
    Console.ReadLine();
    return;
}

var ds4 = client.CreateDualShock4Controller();
ds4.AutoSubmitReport = false;
ds4.Connect();
Console.WriteLine("[+] Virtual DualShock 4 aktif.");

// Tampilkan IP lokal
Console.WriteLine("[i] Alamat IP PC ini:");
foreach (var ni in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
{
    if (ni.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
    if (ni.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback) continue;
    foreach (var addr in ni.GetIPProperties().UnicastAddresses)
    {
        if (addr.Address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
            Console.WriteLine($"    {ni.Name}: {addr.Address}");
    }
}

// TCP Server
var listener = new TcpListener(IPAddress.Any, PORT);
listener.Start();
Console.WriteLine($"[i] Menunggu koneksi di port {PORT}...");
Console.WriteLine("[i] Buka app Android, masukkan IP di atas, tekan Connect.");
Console.WriteLine("[i] Pastikan firewall Windows mengizinkan port " + PORT + ".");

// State DS4 yang akan dikirim
byte lx = 128, ly = 128, rx = 128, ry = 128, l2 = 0, r2 = 0, dpad = 8;
bool cross = false, circle = false, square = false, triangle = false;
bool l1 = false, r1 = false, share = false, options = false;
bool l3 = false, r3 = false, ps = false, touchpad = false;

bool DPadUp()    => dpad == 0 || dpad == 1 || dpad == 7;
bool DPadRight() => dpad == 1 || dpad == 2 || dpad == 3;
bool DPadDown()  => dpad == 3 || dpad == 4 || dpad == 5;
bool DPadLeft()  => dpad == 5 || dpad == 6 || dpad == 7;

void PushToDs4()
{
    ds4.SetAxisValue(DualShock4Axis.LeftThumbX,  lx);
    ds4.SetAxisValue(DualShock4Axis.LeftThumbY,  ly);
    ds4.SetAxisValue(DualShock4Axis.RightThumbX, rx);
    ds4.SetAxisValue(DualShock4Axis.RightThumbY, ry);
    ds4.SetSliderValue(DualShock4Slider.LeftTrigger,  l2);
    ds4.SetSliderValue(DualShock4Slider.RightTrigger, r2);

    ds4.SetButtonState(DualShock4Button.Cross,    cross);
    ds4.SetButtonState(DualShock4Button.Circle,   circle);
    ds4.SetButtonState(DualShock4Button.Square,   square);
    ds4.SetButtonState(DualShock4Button.Triangle, triangle);
    ds4.SetButtonState(DualShock4Button.ShoulderLeft,  l1);
    ds4.SetButtonState(DualShock4Button.ShoulderRight, r1);
    ds4.SetButtonState(DualShock4Button.Share,    share);
    ds4.SetButtonState(DualShock4Button.Options,  options);
    ds4.SetButtonState(DualShock4Button.ThumbLeft,  l3);
    ds4.SetButtonState(DualShock4Button.ThumbRight, r3);
    ds4.SetButtonState(DualShock4Button.Ps,       ps);
    ds4.SetButtonState(DualShock4Button.Touchpad, touchpad);

    ds4.SetDPadDirection(
        DPadUp() && DPadRight() ? DualShock4DPadDirection.Northeast :
        DPadUp() && DPadLeft()  ? DualShock4DPadDirection.Northwest :
        DPadDown() && DPadRight()? DualShock4DPadDirection.Southeast :
        DPadDown() && DPadLeft() ? DualShock4DPadDirection.Southwest :
        DPadUp()    ? DualShock4DPadDirection.North :
        DPadRight() ? DualShock4DPadDirection.East :
        DPadDown()  ? DualShock4DPadDirection.South :
        DPadLeft()  ? DualShock4DPadDirection.West :
                      DualShock4DPadDirection.Neutral);

    ds4.SubmitReport();
}

while (true)
{
    Console.WriteLine("\n[..] Menunggu HP terhubung...");
    using var tcp = listener.AcceptTcpClient();
    Console.WriteLine("[+] Terhubung dari " + tcp.Client.RemoteEndPoint);

    tcp.NoDelay = true; // disable Nagle untuk latensi rendah
    var stream = tcp.GetStream();
    var buf = new byte[PACKET_SIZE];
    var lastReceive = DateTime.Now;

    try
    {
        while (tcp.Connected)
        {
            int read = 0;
            while (read < PACKET_SIZE)
            {
                int n = stream.Read(buf, read, PACKET_SIZE - read);
                if (n <= 0) throw new Exception("Client disconnected");
                read += n;
            }

            // Validasi sync byte
            if (buf[0] != SYNC) continue;

            // Checksum (XOR byte 1..10)
            byte ck = 0;
            for (int i = 1; i < PACKET_SIZE - 1; i++) ck ^= buf[i];
            if (ck != buf[PACKET_SIZE - 1]) continue;

            // Parse
            ushort btns = (ushort)(buf[1] | (buf[2] << 8));
            cross    = (btns & 0x0001) != 0;
            circle   = (btns & 0x0002) != 0;
            square   = (btns & 0x0004) != 0;
            triangle = (btns & 0x0008) != 0;
            l1       = (btns & 0x0010) != 0;
            r1       = (btns & 0x0020) != 0;
            share    = (btns & 0x0040) != 0;
            options  = (btns & 0x0080) != 0;
            l3       = (btns & 0x0100) != 0;
            r3       = (btns & 0x0200) != 0;
            ps       = (btns & 0x0400) != 0;
            touchpad = (btns & 0x0800) != 0;

            lx = buf[3]; ly = buf[4];
            rx = buf[5]; ry = buf[6];
            l2 = buf[7]; r2 = buf[8];
            dpad = buf[9];

            PushToDs4();
            lastReceive = DateTime.Now;
        }
    }
    catch (Exception ex)
    {
        Console.WriteLine("[-] Koneksi berakhir: " + ex.Message);
    }

    // Reset ke netral saat disconnect
    cross = circle = square = triangle = false;
    l1 = r1 = share = options = l3 = r3 = ps = touchpad = false;
    lx = ly = rx = ry = 128; l2 = r2 = 0; dpad = 8;
    PushToDs4();
}