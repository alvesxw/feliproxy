// FeliProxy - liga/desliga o ProxiFyre (proxy SOCKS5 só para o Discord)
// e permite trocar o proxy (endereço, usuário, senha, protocolos).
// Compila com o csc do .NET Framework 4.x (C# 5): veja build.ps1.

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.ServiceProcess;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("FeliProxy")]
[assembly: AssemblyProduct("FeliProxy")]
[assembly: AssemblyDescription("Liga/desliga o proxy do Discord (ProxiFyre)")]
// Versão: gerada pelo build.ps1 a partir do arquivo VERSION (obj\AssemblyVersion.cs)

namespace FeliProxy
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            bool created;
            using (var mutex = new Mutex(true, "Global\\FeliProxyApp", out created))
            {
                if (!created) return; // já existe uma janela aberta
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new MainForm());
            }
        }
    }

    public class ProxyConfig
    {
        public string Endpoint = "";
        public string Username = "";
        public string Password = "";
        public bool Tcp = true;
        public bool Udp = false;
    }

    // ------------------------------------------------------------------
    // ProxiFyre: serviço, configuração, instalador embutido, Discord
    // ------------------------------------------------------------------
    public static class Tunnel
    {
        public const string ServiceName = "ProxiFyreService";
        public static readonly string[] DiscordNames = { "Discord", "DiscordPTB", "DiscordCanary" };
        const string SetupResource = "ProxiFyreSetup.exe";

        public static string InstallDir()
        {
            try
            {
                using (var k = Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Services\" + ServiceName))
                {
                    if (k != null)
                    {
                        string p = (k.GetValue("ImagePath") as string ?? "").Trim();
                        if (p.StartsWith("\""))
                        {
                            int e = p.IndexOf('"', 1);
                            if (e > 1) p = p.Substring(1, e - 1);
                        }
                        else
                        {
                            int x = p.IndexOf(".exe", StringComparison.OrdinalIgnoreCase);
                            if (x > 0) p = p.Substring(0, x + 4);
                        }
                        if (p.Length > 0) return Path.GetDirectoryName(p);
                    }
                }
            }
            catch { }
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "ProxiFyre");
        }

        public static string ConfigPath { get { return Path.Combine(InstallDir(), "app-config.json"); } }

        public static ServiceControllerStatus? Status()
        {
            try { using (var sc = new ServiceController(ServiceName)) { return sc.Status; } }
            catch (InvalidOperationException) { return null; }
        }

        public static void Start()
        {
            using (var sc = new ServiceController(ServiceName))
            {
                if (sc.Status == ServiceControllerStatus.Running) return;
                if (sc.Status != ServiceControllerStatus.StartPending) sc.Start();
                sc.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(30));
            }
        }

        public static void Stop()
        {
            using (var sc = new ServiceController(ServiceName))
            {
                if (sc.Status == ServiceControllerStatus.Stopped) return;
                if (sc.Status != ServiceControllerStatus.StopPending) sc.Stop();
                sc.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(30));
            }
        }

        public static void Restart() { Stop(); Start(); }

        public static bool HasConfig()
        {
            var c = Load();
            return c.Endpoint.Length > 0;
        }

        static Dictionary<string, object> LoadRaw()
        {
            try
            {
                var o = new JavaScriptSerializer().DeserializeObject(File.ReadAllText(ConfigPath)) as Dictionary<string, object>;
                if (o != null) return o;
            }
            catch { }
            return new Dictionary<string, object>();
        }

        static Dictionary<string, object> FirstProxy(Dictionary<string, object> raw)
        {
            object v;
            if (raw.TryGetValue("proxies", out v))
            {
                var arr = v as object[];
                if (arr != null && arr.Length > 0) return arr[0] as Dictionary<string, object>;
            }
            return null;
        }

        static string Str(Dictionary<string, object> d, string key)
        {
            object v;
            return d != null && d.TryGetValue(key, out v) && v != null ? Convert.ToString(v) : "";
        }

        static List<string> StrList(Dictionary<string, object> d, string key)
        {
            object v;
            var arr = (d != null && d.TryGetValue(key, out v)) ? v as object[] : null;
            return arr == null ? new List<string>() : arr.Select(x => Convert.ToString(x)).ToList();
        }

        public static ProxyConfig Load()
        {
            var cfg = new ProxyConfig();
            var p = FirstProxy(LoadRaw());
            if (p == null) return cfg;
            cfg.Endpoint = Str(p, "socks5ProxyEndpoint");
            cfg.Username = Str(p, "username");
            cfg.Password = Str(p, "password");
            if (p.ContainsKey("supportedProtocols"))
            {
                var list = StrList(p, "supportedProtocols").Select(x => x.ToUpperInvariant()).ToList();
                cfg.Tcp = list.Contains("TCP");
                cfg.Udp = list.Contains("UDP");
            }
            else { cfg.Tcp = true; cfg.Udp = true; } // padrão do ProxiFyre
            return cfg;
        }

        static string Q(string s) { return new JavaScriptSerializer().Serialize(s ?? ""); }
        static string Arr(IEnumerable<string> items) { return "[" + string.Join(", ", items.Select(Q)) + "]"; }

        public static void Save(ProxyConfig c)
        {
            var raw = LoadRaw();
            var old = FirstProxy(raw);
            string logLevel = raw.ContainsKey("logLevel") ? Convert.ToString(raw["logLevel"]) : "Info";
            bool bypass = true;
            if (raw.ContainsKey("bypassLan")) { try { bypass = Convert.ToBoolean(raw["bypassLan"]); } catch { } }
            var apps = StrList(old, "appNames");
            if (apps.Count == 0) apps = DiscordNames.ToList();
            var fams = StrList(old, "supportedAddressFamilies");
            if (fams.Count == 0) fams = new List<string> { "IPv4" };
            var excludes = StrList(raw, "excludes");
            var protos = new List<string>();
            if (c.Tcp) protos.Add("TCP");
            if (c.Udp) protos.Add("UDP");

            var sb = new StringBuilder();
            sb.AppendLine("{");
            sb.AppendLine("  \"logLevel\": " + Q(logLevel) + ",");
            sb.AppendLine("  \"bypassLan\": " + (bypass ? "true" : "false") + ",");
            sb.AppendLine("  \"proxies\": [");
            sb.AppendLine("    {");
            sb.AppendLine("      \"appNames\": " + Arr(apps) + ",");
            sb.AppendLine("      \"socks5ProxyEndpoint\": " + Q(c.Endpoint) + ",");
            if (c.Username.Length > 0)
            {
                sb.AppendLine("      \"username\": " + Q(c.Username) + ",");
                sb.AppendLine("      \"password\": " + Q(c.Password) + ",");
            }
            sb.AppendLine("      \"supportedProtocols\": " + Arr(protos) + ",");
            sb.AppendLine("      \"supportedAddressFamilies\": " + Arr(fams));
            sb.AppendLine("    }");
            sb.AppendLine("  ],");
            sb.AppendLine("  \"excludes\": " + Arr(excludes));
            sb.AppendLine("}");

            Directory.CreateDirectory(InstallDir());
            string tmp = ConfigPath + ".tmp";
            File.WriteAllText(tmp, sb.ToString(), new UTF8Encoding(false));
            if (File.Exists(ConfigPath)) File.Replace(tmp, ConfigPath, null);
            else File.Move(tmp, ConfigPath);
        }

        public static bool HasEmbeddedInstaller()
        {
            return Assembly.GetExecutingAssembly().GetManifestResourceNames().Contains(SetupResource);
        }

        // Extrai o instalador oficial do ProxiFyre embutido no .exe e roda.
        public static int InstallProxiFyre()
        {
            string dir = Path.Combine(Path.GetTempPath(), "FeliProxy_" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(dir);
            string exe = Path.Combine(dir, SetupResource);
            try
            {
                using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream(SetupResource))
                using (var f = File.Create(exe))
                    s.CopyTo(f);
                var psi = new ProcessStartInfo(exe, "/passive /norestart");
                psi.UseShellExecute = false;
                using (var p = Process.Start(psi))
                {
                    p.WaitForExit();
                    return p.ExitCode;
                }
            }
            finally
            {
                try { Directory.Delete(dir, true); } catch { }
            }
        }

        static string FindShortcut(string name)
        {
            var roots = new[] {
                Environment.GetFolderPath(Environment.SpecialFolder.StartMenu),
                Environment.GetFolderPath(Environment.SpecialFolder.CommonStartMenu) };
            foreach (var r in roots)
            {
                try
                {
                    var f = Directory.GetFiles(r, name + ".lnk", SearchOption.AllDirectories).FirstOrDefault();
                    if (f != null) return f;
                }
                catch { }
            }
            return null;
        }

        // Fecha e reabre o Discord. Reabre pelo explorer para ele NÃO rodar como administrador.
        public static bool RestartDiscord()
        {
            var running = new List<string>();
            foreach (var n in DiscordNames)
            {
                var ps = Process.GetProcessesByName(n);
                if (ps.Length > 0) running.Add(n);
                foreach (var p in ps) { try { p.Kill(); } catch { } p.Dispose(); }
            }
            if (running.Count == 0) return false;
            Thread.Sleep(1500);
            foreach (var n in running)
            {
                string lnk = FindShortcut(n);
                if (lnk == null) continue;
                var psi = new ProcessStartInfo("explorer.exe", "\"" + lnk + "\"");
                psi.UseShellExecute = false;
                Process.Start(psi);
            }
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Teste do proxy SOCKS5 (TCP CONNECT + UDP ASSOCIATE)
    // ------------------------------------------------------------------
    public class TestResult
    {
        public bool TcpOk;
        public string Tcp = "";
        public bool UdpOk;
        public string Udp = "";
    }

    public static class Socks5
    {
        public static void ParseEndpoint(string ep, out string host, out int port)
        {
            int i = (ep ?? "").LastIndexOf(':');
            if (i <= 0 || !int.TryParse(ep.Substring(i + 1), out port) || port < 1 || port > 65535)
                throw new FormatException("Endereço inválido. Use IP:porta, por exemplo 1.2.3.4:1080");
            host = ep.Substring(0, i).Trim();
            if (host.Length == 0) throw new FormatException("Endereço inválido. Use IP:porta, por exemplo 1.2.3.4:1080");
        }

        static IPAddress Resolve(string host)
        {
            IPAddress ip;
            if (IPAddress.TryParse(host, out ip)) return ip;
            var a = Dns.GetHostAddresses(host).FirstOrDefault(x => x.AddressFamily == AddressFamily.InterNetwork);
            if (a == null) throw new IOException("não foi possível encontrar o IPv4 de " + host);
            return a;
        }

        static void ReadExact(Stream s, byte[] buf, int len)
        {
            int o = 0;
            while (o < len)
            {
                int n = s.Read(buf, o, len - o);
                if (n <= 0) throw new IOException("o proxy fechou a conexão");
                o += n;
            }
        }

        static string ReplyText(byte rep)
        {
            switch (rep)
            {
                case 1: return "falha geral do servidor";
                case 2: return "não permitido pelas regras do proxy";
                case 3: return "rede inacessível";
                case 4: return "host inacessível";
                case 5: return "conexão recusada";
                case 6: return "tempo esgotado";
                case 7: return "comando não suportado";
                case 8: return "tipo de endereço não suportado";
                default: return "código 0x" + rep.ToString("X2");
            }
        }

        static TcpClient Open(IPAddress ip, int port, string user, string pass, out NetworkStream s)
        {
            var c = new TcpClient(AddressFamily.InterNetwork);
            try
            {
                c.ReceiveTimeout = 8000;
                c.SendTimeout = 8000;
                var ar = c.BeginConnect(ip, port, null, null);
                if (!ar.AsyncWaitHandle.WaitOne(8000)) throw new System.TimeoutException("o proxy não respondeu (tempo esgotado)");
                c.EndConnect(ar);
                s = c.GetStream();

                bool hasAuth = !string.IsNullOrEmpty(user);
                byte[] greet = hasAuth ? new byte[] { 5, 2, 0, 2 } : new byte[] { 5, 1, 0 };
                s.Write(greet, 0, greet.Length);
                var r = new byte[2];
                ReadExact(s, r, 2);
                if (r[0] != 5) throw new IOException("o servidor não é um proxy SOCKS5");
                if (r[1] == 0xFF) throw new IOException(hasAuth ? "o proxy recusou o login" : "o proxy exige usuário e senha");
                if (r[1] == 2)
                {
                    if (!hasAuth) throw new IOException("o proxy exige usuário e senha");
                    byte[] u = Encoding.UTF8.GetBytes(user), p = Encoding.UTF8.GetBytes(pass ?? "");
                    if (u.Length > 255 || p.Length > 255) throw new IOException("usuário ou senha longos demais");
                    var a = new byte[3 + u.Length + p.Length];
                    a[0] = 1;
                    a[1] = (byte)u.Length;
                    Buffer.BlockCopy(u, 0, a, 2, u.Length);
                    a[2 + u.Length] = (byte)p.Length;
                    Buffer.BlockCopy(p, 0, a, 3 + u.Length, p.Length);
                    s.Write(a, 0, a.Length);
                    ReadExact(s, r, 2);
                    if (r[1] != 0) throw new IOException("usuário ou senha incorretos");
                }
                else if (r[1] != 0) throw new IOException("método de autenticação não suportado");
                return c;
            }
            catch
            {
                c.Close();
                throw;
            }
        }

        // Lê a resposta de um comando. Retorna null se o proxy recusou (rep != 0).
        static IPEndPoint ReadReply(Stream s, out byte rep)
        {
            var h = new byte[4];
            ReadExact(s, h, 4);
            rep = h[1];
            if (rep != 0) return null;
            IPAddress addr = IPAddress.Any;
            if (h[3] == 1) { var b = new byte[4]; ReadExact(s, b, 4); addr = new IPAddress(b); }
            else if (h[3] == 4) { var b = new byte[16]; ReadExact(s, b, 16); addr = new IPAddress(b); }
            else if (h[3] == 3)
            {
                var l = new byte[1]; ReadExact(s, l, 1);
                var b = new byte[l[0]]; ReadExact(s, b, l[0]);
                try { addr = Resolve(Encoding.ASCII.GetString(b)); } catch { }
            }
            var pb = new byte[2];
            ReadExact(s, pb, 2);
            return new IPEndPoint(addr, (pb[0] << 8) | pb[1]);
        }

        static byte[] DnsQuery(string name)
        {
            var ms = new MemoryStream();
            ms.Write(new byte[] { 0x44, 0x54, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0 }, 0, 12);
            foreach (var label in name.Split('.'))
            {
                var b = Encoding.ASCII.GetBytes(label);
                ms.WriteByte((byte)b.Length);
                ms.Write(b, 0, b.Length);
            }
            ms.WriteByte(0);
            ms.Write(new byte[] { 0, 1, 0, 1 }, 0, 4);
            return ms.ToArray();
        }

        public static TestResult Test(string endpoint, string user, string pass)
        {
            var res = new TestResult();
            string host; int port;
            ParseEndpoint(endpoint, out host, out port);
            IPAddress ip = Resolve(host);

            // TCP: CONNECT em api.ipify.org:80 e lê o IP de saída
            try
            {
                NetworkStream s;
                using (Open(ip, port, user, pass, out s))
                {
                    byte[] hb = Encoding.ASCII.GetBytes("api.ipify.org");
                    var req = new byte[7 + hb.Length];
                    req[0] = 5; req[1] = 1; req[2] = 0; req[3] = 3; req[4] = (byte)hb.Length;
                    Buffer.BlockCopy(hb, 0, req, 5, hb.Length);
                    req[5 + hb.Length] = 0; req[6 + hb.Length] = 80;
                    s.Write(req, 0, req.Length);
                    byte rep;
                    ReadReply(s, out rep);
                    if (rep != 0) throw new IOException("conexão recusada pelo proxy (" + ReplyText(rep) + ")");
                    var http = Encoding.ASCII.GetBytes("GET / HTTP/1.1\r\nHost: api.ipify.org\r\nConnection: close\r\n\r\n");
                    s.Write(http, 0, http.Length);
                    var ms = new MemoryStream();
                    var buf = new byte[4096];
                    try { int n; while ((n = s.Read(buf, 0, buf.Length)) > 0 && ms.Length < 65536) ms.Write(buf, 0, n); }
                    catch (IOException) { }
                    string text = Encoding.ASCII.GetString(ms.ToArray());
                    int b = text.IndexOf("\r\n\r\n");
                    string exitIp = null;
                    if (b >= 0)
                    {
                        foreach (var line in text.Substring(b + 4).Split('\n'))
                        {
                            IPAddress tmp;
                            if (IPAddress.TryParse(line.Trim(), out tmp)) { exitIp = tmp.ToString(); break; }
                        }
                    }
                    res.TcpOk = true;
                    res.Tcp = "TCP: OK" + (exitIp != null ? " (IP de saída " + exitIp + ")" : "");
                }
            }
            catch (Exception ex)
            {
                res.TcpOk = false;
                res.Tcp = "TCP: falhou (" + ex.Message + ")";
                res.Udp = "UDP: não testado";
                return res;
            }

            // UDP: UDP ASSOCIATE + uma consulta DNS para 1.1.1.1 pelo relay
            try
            {
                NetworkStream s;
                using (Open(ip, port, user, pass, out s))
                using (var u = new UdpClient(new IPEndPoint(IPAddress.Any, 0)))
                {
                    var req = new byte[] { 5, 3, 0, 1, 0, 0, 0, 0, 0, 0 };
                    s.Write(req, 0, req.Length);
                    byte rep;
                    var relay = ReadReply(s, out rep);
                    if (rep != 0)
                    {
                        res.UdpOk = false;
                        res.Udp = "UDP: não suportado por este proxy (" + ReplyText(rep) + "). A voz não passa por ele.";
                        return res;
                    }
                    if (relay.Address.Equals(IPAddress.Any) || relay.Address.Equals(IPAddress.IPv6Any))
                        relay = new IPEndPoint(ip, relay.Port);

                    byte[] dns = DnsQuery("discord.com");
                    var pkt = new byte[10 + dns.Length];
                    pkt[3] = 1; pkt[4] = 1; pkt[5] = 1; pkt[6] = 1; pkt[7] = 1; pkt[8] = 0; pkt[9] = 53;
                    Buffer.BlockCopy(dns, 0, pkt, 10, dns.Length);
                    u.Client.ReceiveTimeout = 4000;
                    bool got = false;
                    for (int i = 0; i < 2 && !got; i++)
                    {
                        u.Send(pkt, pkt.Length, relay);
                        try
                        {
                            var from = new IPEndPoint(IPAddress.Any, 0);
                            got = u.Receive(ref from).Length > 10;
                        }
                        catch (SocketException) { }
                    }
                    res.UdpOk = got;
                    res.Udp = got ? "UDP: OK, a voz deve funcionar pelo proxy."
                                  : "UDP: o proxy aceitou, mas nenhuma resposta voltou. A voz pode não funcionar.";
                }
            }
            catch (Exception ex)
            {
                res.UdpOk = false;
                res.Udp = "UDP: falhou (" + ex.Message + ")";
            }
            return res;
        }
    }

    // ------------------------------------------------------------------
    // Janela
    // ------------------------------------------------------------------
    public class MainForm : Form
    {
        static readonly Color Bg = Color.FromArgb(49, 51, 56);
        static readonly Color InputBg = Color.FromArgb(30, 31, 34);
        static readonly Color Fg = Color.FromArgb(242, 243, 245);
        static readonly Color Muted = Color.FromArgb(181, 186, 193);
        static readonly Color Dim = Color.FromArgb(128, 132, 142);
        static readonly Color Green = Color.FromArgb(35, 165, 90);
        static readonly Color Red = Color.FromArgb(218, 55, 60);
        static readonly Color Yellow = Color.FromArgb(240, 178, 50);
        static readonly Color Blurple = Color.FromArgb(88, 101, 242);
        static readonly Color ButtonGray = Color.FromArgb(78, 80, 88);

        Label dot, status, info, result;
        Button toggle, btnTest, btnSave;
        CheckBox chkRestart, chkShow, chkTcp, chkUdp;
        TextBox txtEndpoint, txtUser, txtPass;
        System.Windows.Forms.Timer timer;
        bool busy;

        public MainForm()
        {
            SuspendLayout();
            AutoScaleDimensions = new SizeF(96F, 96F);
            AutoScaleMode = AutoScaleMode.Dpi;
            Text = "FeliProxy";
            ClientSize = new Size(380, 500);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Bg;
            ForeColor = Fg;
            Font = new Font("Segoe UI", 10F);
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            dot = AddLabel(new string((char)0x25CF, 1), 18, 8, new Font("Segoe UI", 22F), Dim);
            status = AddLabel("", 56, 18, new Font("Segoe UI Semibold", 15F), Fg);
            info = AddLabel("", 22, 60, null, Muted);

            toggle = AddButton("", 22, 90, 336, 44, Blurple);
            toggle.Font = new Font("Segoe UI Semibold", 11F);
            toggle.Click += OnToggle;

            chkRestart = AddCheck("Reiniciar o Discord ao ligar/desligar", 22, 142, true);

            AddLabel("Proxy SOCKS5", 22, 182, new Font("Segoe UI Semibold", 11F), Fg);
            AddLabel("Endereço (IP:porta)", 22, 210, new Font("Segoe UI", 9F), Muted);
            txtEndpoint = AddText(22, 230, 336, false);
            AddLabel("Usuário (opcional)", 22, 266, new Font("Segoe UI", 9F), Muted);
            txtUser = AddText(22, 286, 160, false);
            AddLabel("Senha (opcional)", 198, 266, new Font("Segoe UI", 9F), Muted);
            txtPass = AddText(198, 286, 160, true);
            chkShow = AddCheck("Mostrar senha", 198, 316, false);
            chkShow.CheckedChanged += delegate { txtPass.UseSystemPasswordChar = !chkShow.Checked; };

            chkTcp = AddCheck("TCP (texto e imagens)", 22, 346, true);
            chkUdp = AddCheck("UDP (voz)", 210, 346, false);

            btnTest = AddButton("Testar proxy", 22, 382, 160, 36, ButtonGray);
            btnTest.Click += OnTest;
            btnSave = AddButton("Salvar", 198, 382, 160, 36, Blurple);
            btnSave.Click += OnSave;

            result = AddLabel("", 22, 428, new Font("Segoe UI", 9F), Muted);
            result.AutoSize = false;
            result.Size = new Size(336, 64);

            timer = new System.Windows.Forms.Timer();
            timer.Interval = 2000;
            timer.Tick += delegate { RefreshStatus(); };

            ResumeLayout(false);
            PerformLayout();

            LoadFields();
            RefreshStatus();
            timer.Start();
        }

        Label AddLabel(string text, int x, int y, Font font, Color color)
        {
            var l = new Label();
            l.Text = text;
            l.AutoSize = true;
            l.Location = new Point(x, y);
            l.ForeColor = color;
            l.BackColor = Color.Transparent;
            if (font != null) l.Font = font;
            Controls.Add(l);
            return l;
        }

        Button AddButton(string text, int x, int y, int w, int h, Color color)
        {
            var b = new Button();
            b.Text = text;
            b.Location = new Point(x, y);
            b.Size = new Size(w, h);
            b.FlatStyle = FlatStyle.Flat;
            b.FlatAppearance.BorderSize = 0;
            b.BackColor = color;
            b.ForeColor = Color.White;
            b.Cursor = Cursors.Hand;
            b.UseVisualStyleBackColor = false;
            Controls.Add(b);
            return b;
        }

        CheckBox AddCheck(string text, int x, int y, bool isChecked)
        {
            var c = new CheckBox();
            c.Text = text;
            c.AutoSize = true;
            c.Location = new Point(x, y);
            c.Checked = isChecked;
            c.ForeColor = Muted;
            Controls.Add(c);
            return c;
        }

        TextBox AddText(int x, int y, int w, bool password)
        {
            var t = new TextBox();
            t.Location = new Point(x, y);
            t.Width = w;
            t.BorderStyle = BorderStyle.FixedSingle;
            t.BackColor = InputBg;
            t.ForeColor = Fg;
            t.UseSystemPasswordChar = password;
            Controls.Add(t);
            return t;
        }

        void LoadFields()
        {
            var c = Tunnel.Load();
            txtEndpoint.Text = c.Endpoint;
            txtUser.Text = c.Username;
            txtPass.Text = c.Password;
            if (c.Endpoint.Length > 0) { chkTcp.Checked = c.Tcp; chkUdp.Checked = c.Udp; }
        }

        void SetResult(string text, Color color)
        {
            result.Text = text;
            result.ForeColor = color;
        }

        void SetBusy(bool on, string statusText)
        {
            busy = on;
            foreach (Control c in Controls)
                if (c is Button || c is TextBox || c is CheckBox) c.Enabled = !on;
            if (on && statusText != null) { status.Text = statusText; dot.ForeColor = Yellow; }
            Cursor = on ? Cursors.WaitCursor : Cursors.Default;
            if (!on) RefreshStatus();
        }

        void RefreshStatus()
        {
            if (busy) return;
            var st = Tunnel.Status();
            var cfg = Tunnel.Load();
            var protos = new List<string>();
            if (cfg.Tcp) protos.Add("TCP");
            if (cfg.Udp) protos.Add("UDP");
            info.Text = cfg.Endpoint.Length > 0
                ? "Salvo: " + cfg.Endpoint + "  (" + string.Join(" + ", protos) + (cfg.Username.Length > 0 ? ", com login" : "") + ")"
                : "Nenhum proxy salvo ainda";

            if (st == null)
            {
                dot.ForeColor = Red; status.Text = "ProxiFyre não instalado";
                toggle.Text = "Instalar ProxiFyre"; toggle.BackColor = Blurple;
                toggle.Enabled = Tunnel.HasEmbeddedInstaller();
            }
            else if (st == ServiceControllerStatus.Running)
            {
                dot.ForeColor = Green; status.Text = "Ativado";
                toggle.Text = "Desativar"; toggle.BackColor = Red; toggle.Enabled = true;
            }
            else if (st == ServiceControllerStatus.Stopped)
            {
                dot.ForeColor = Dim; status.Text = "Desativado";
                toggle.Text = "Ativar"; toggle.BackColor = Blurple; toggle.Enabled = true;
            }
            else
            {
                dot.ForeColor = Yellow; status.Text = "Aguarde...";
                toggle.Enabled = false;
            }
        }

        string ReadForm(out ProxyConfig c, bool forSave)
        {
            c = new ProxyConfig();
            c.Endpoint = txtEndpoint.Text.Trim();
            c.Username = txtUser.Text.Trim();
            c.Password = txtPass.Text;
            c.Tcp = chkTcp.Checked;
            c.Udp = chkUdp.Checked;
            try { string h; int p; Socks5.ParseEndpoint(c.Endpoint, out h, out p); }
            catch (FormatException ex) { return ex.Message; }
            if ((c.Username.Length > 0) != (c.Password.Length > 0))
                return "Preencha usuário e senha juntos, ou deixe os dois vazios.";
            if (forSave && !c.Tcp && !c.Udp) return "Marque pelo menos um protocolo (TCP e/ou UDP).";
            return null;
        }

        async void OnToggle(object sender, EventArgs e)
        {
            var st = Tunnel.Status();
            if (st == null) { await Install(); return; }

            bool running = st == ServiceControllerStatus.Running;
            if (!running && !Tunnel.HasConfig())
            {
                SetResult("Preencha o proxy e clique em Salvar antes de ativar.", Yellow);
                return;
            }
            bool restartDiscord = chkRestart.Checked;
            SetBusy(true, running ? "Desativando..." : "Ativando...");
            string msg = null, err = null;
            try
            {
                msg = await Task.Run(() =>
                {
                    if (running) Tunnel.Stop(); else Tunnel.Start();
                    if (!restartDiscord) return "Reinicie o Discord para valer nas conexões já abertas.";
                    return Tunnel.RestartDiscord() ? "Discord reiniciado." : "O Discord não estava aberto.";
                });
            }
            catch (Exception ex) { err = ex.Message; }
            SetBusy(false, null);
            if (err != null) SetResult("Erro: " + err, Red);
            else SetResult((running ? "Desativado. " : "Ativado. ") + msg, Muted);
        }

        async Task Install()
        {
            SetBusy(true, "Instalando o ProxiFyre...");
            SetResult("Instalando o ProxiFyre e o driver Windows Packet Filter. Isso pode levar um minuto.", Muted);
            int code = -1; string err = null;
            try { code = await Task.Run(() => Tunnel.InstallProxiFyre()); }
            catch (Exception ex) { err = ex.Message; }
            SetBusy(false, null);
            if (err != null) SetResult("Erro ao instalar: " + err, Red);
            else if (code == 0) SetResult("ProxiFyre instalado. Preencha o proxy, clique em Salvar e depois em Ativar.", Green);
            else if (code == 3010) SetResult("ProxiFyre instalado. O Windows pede uma reinicialização antes de usar.", Yellow);
            else SetResult("O instalador terminou com o código " + code + ".", Red);
        }

        async void OnTest(object sender, EventArgs e)
        {
            ProxyConfig c;
            string verr = ReadForm(out c, false);
            if (verr != null) { SetResult(verr, Red); return; }
            SetBusy(true, null);
            SetResult("Testando o proxy...", Muted);
            TestResult r = null; string err = null;
            try { r = await Task.Run(() => Socks5.Test(c.Endpoint, c.Username, c.Password)); }
            catch (Exception ex) { err = ex.Message; }
            SetBusy(false, null);
            if (err != null) { SetResult("Erro: " + err, Red); return; }

            string text = r.Tcp + "\n" + r.Udp;
            if (r.TcpOk && !r.UdpOk && chkUdp.Checked) text += "\nDica: desmarque UDP e salve, para a voz sair pela sua conexão.";
            if (r.TcpOk && r.UdpOk && !chkUdp.Checked) text += "\nDica: marque UDP e salve, para a voz também passar pelo proxy.";
            SetResult(text, !r.TcpOk ? Red : (r.UdpOk ? Green : Yellow));
        }

        async void OnSave(object sender, EventArgs e)
        {
            if (Tunnel.Status() == null) { SetResult("Instale o ProxiFyre primeiro.", Yellow); return; }
            ProxyConfig c;
            string verr = ReadForm(out c, true);
            if (verr != null) { SetResult(verr, Red); return; }
            bool restartDiscord = chkRestart.Checked;
            SetBusy(true, "Salvando...");
            string msg = null, err = null;
            try
            {
                msg = await Task.Run(() =>
                {
                    Tunnel.Save(c);
                    if (Tunnel.Status() != ServiceControllerStatus.Running) return "Salvo. Clique em Ativar para usar.";
                    Tunnel.Restart();
                    if (restartDiscord) Tunnel.RestartDiscord();
                    return "Salvo. O serviço foi reiniciado com o novo proxy" + (restartDiscord ? " e o Discord também." : ".");
                });
            }
            catch (Exception ex) { err = ex.Message; }
            SetBusy(false, null);
            if (err != null) SetResult("Erro ao salvar: " + err, Red);
            else SetResult(msg, Green);
        }

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            timer.Stop();
            base.OnFormClosed(e);
        }
    }
}
