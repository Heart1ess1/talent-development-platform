using System.Diagnostics;
using System.Net.Sockets;
using System.Text;

namespace TalentPlatformLauncher;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        using var mutex = new Mutex(true, "TalentPlatformLauncher.Singleton", out var first);
        if (!first) { MessageBox.Show("启动器已经在运行。", "人才培养平台"); return; }
        ApplicationConfiguration.Initialize();
        Application.Run(new LauncherForm());
    }
}

internal sealed class LauncherForm : Form
{
    private readonly Button startButton = new() { Text = "启动平台", Width = 110, Height = 36 };
    private readonly Button updateButton = new() { Text = "更新并启动", Width = 110, Height = 36 };
    private readonly Button openButton = new() { Text = "打开系统", Width = 110, Height = 36, Enabled = false };
    private readonly Button stopButton = new() { Text = "停止平台", Width = 110, Height = 36, Enabled = false };
    private readonly Button stopDatabaseButton = new() { Text = "停止数据库", Width = 110, Height = 36 };
    private readonly Label statusLabel = new() { Text = "尚未启动", AutoSize = true, ForeColor = Color.DimGray, Font = new Font("Microsoft YaHei UI", 11, FontStyle.Bold) };
    private readonly TextBox logs = new() { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill, BackColor = Color.FromArgb(248, 249, 250), Font = new Font("Consolas", 9) };
    private readonly HttpClient http = new() { Timeout = TimeSpan.FromSeconds(2) };
    private readonly string root;
    private readonly string? sourceRoot;
    private Process? backend;
    private CancellationTokenSource? startupCancellation;

    public LauncherForm()
    {
        Text = "人才培养平台启动器"; Width = 760; Height = 520; StartPosition = FormStartPosition.CenterScreen; MinimumSize = new Size(660, 420);
        root = FindRoot();
        sourceRoot = FindSourceRoot(root);
        updateButton.Enabled = sourceRoot != null;
        var header = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 66, Padding = new Padding(12), FlowDirection = FlowDirection.LeftToRight };
        header.Controls.AddRange([startButton, updateButton, openButton, stopButton, stopDatabaseButton, statusLabel]);
        Controls.Add(logs); Controls.Add(header);
        startButton.Click += async (_, _) => await StartAsync();
        updateButton.Click += async (_, _) => await RebuildAndStartAsync();
        openButton.Click += (_, _) => OpenBrowser();
        stopButton.Click += (_, _) => StopBackend();
        stopDatabaseButton.Click += async (_, _) => await StopDatabaseAsync();
        FormClosing += (_, _) => { startupCancellation?.Cancel(); StopBackend(); };
        Append($"工作目录：{root}");
        Append(sourceRoot == null ? "未检测到项目源码，“更新并启动”不可用。" : $"源码目录：{sourceRoot}");
    }

    private async Task RebuildAndStartAsync()
    {
        if (sourceRoot == null) { MessageBox.Show("当前发布目录附近未找到 frontend 和 backend 源码。", "无法更新"); return; }
        StopBackend(); updateButton.Enabled = startButton.Enabled = false; startupCancellation = new CancellationTokenSource(); var token = startupCancellation.Token; var built = false;
        try
        {
            SetStatus("正在准备更新…", Color.DarkOrange);
            var docker = await EnsureDockerAsync(token);
            if (await PortInUseAsync(8080)) throw new InvalidOperationException("端口 8080 仍被占用，请先关闭旧平台后重试。");

            SetStatus("正在构建前端…", Color.DarkOrange); Append("开始构建前端资源…");
            var frontendExit = await RunStreamingAsync(docker,
            [
                "run", "--rm", "-v", $"{sourceRoot}:/workspace",
                "--mount", "type=volume,source=talent-frontend-node-modules,target=/workspace/frontend/node_modules",
                "-w", "/workspace/frontend", "node:24-bookworm-slim", "sh", "-lc",
                "corepack enable && corepack pnpm install --frozen-lockfile && corepack pnpm run build"
            ], sourceRoot, token);
            if (frontendExit != 0) throw new InvalidOperationException("前端构建失败，请查看启动器日志。");

            SetStatus("正在打包后端…", Color.DarkOrange); Append("开始打包后端及最新前端资源…");
            var backendExit = await RunStreamingAsync(docker,
            [
                "run", "--rm", "-v", $"{sourceRoot}:/workspace",
                "--mount", "type=volume,source=talent-maven-repo,target=/root/.m2",
                "-w", "/workspace/backend", "maven:3.9.11-eclipse-temurin-17",
                "mvn", "clean", "package", "-DskipTests"
            ], sourceRoot, token);
            if (backendExit != 0) throw new InvalidOperationException("后端打包失败，请查看启动器日志。");

            var builtJar = Path.Combine(sourceRoot, "backend", "target", "talent-platform-0.1.0.jar");
            if (!File.Exists(builtJar)) throw new FileNotFoundException("构建完成但未找到 JAR", builtJar);
            var deployedJar = FindJar();
            File.Copy(builtJar, deployedJar, true);
            Append($"发布包已更新：{deployedJar}"); built = true;
        }
        catch (OperationCanceledException) { SetStatus("更新已取消", Color.DimGray); }
        catch (Exception ex) { Append("更新失败：" + ex.Message); SetStatus("更新失败", Color.Firebrick); MessageBox.Show(ex.Message, "更新失败", MessageBoxButtons.OK, MessageBoxIcon.Error); }
        finally { updateButton.Enabled = sourceRoot != null; if (!built) startButton.Enabled = true; }
        if (built) await StartAsync();
    }

    private async Task StartAsync()
    {
        startButton.Enabled = false; startupCancellation = new CancellationTokenSource(); var token = startupCancellation.Token;
        try
        {
            if (await HealthyAsync()) { SetRunning(); Append("平台已经在运行。"); return; }
            SetStatus("正在检查运行环境…", Color.DarkOrange);
            var docker = await EnsureDockerAsync(token);
            SetStatus("正在启动数据库…", Color.DarkOrange);
            var compose = Path.Combine(root, "docker-compose.yml");
            if (!File.Exists(compose)) throw new FileNotFoundException("缺少 docker-compose.yml", compose);
            var composeExit = await RunAsync(docker, ["compose", "-p", "talent-platform", "-f", compose, "up", "-d", "mysql"], root, token);
            if (composeExit != 0) throw new InvalidOperationException("数据库启动失败，请查看日志。");
            SetStatus("正在启动应用…", Color.DarkOrange);
            var jar = FindJar(); var java = FindJava() ?? throw new InvalidOperationException("未找到 Java 17 或更高版本。");
            if (await PortInUseAsync(8080)) throw new InvalidOperationException("端口 8080 已被其他程序占用。");
            backend = StartBackend(java, jar);
            for (var i = 0; i < 90 && !token.IsCancellationRequested; i++)
            {
                if (backend.HasExited) throw new InvalidOperationException($"应用异常退出，退出码 {backend.ExitCode}。");
                if (await HealthyAsync()) { SetRunning(); Append("平台启动成功：http://localhost:8080"); OpenBrowser(); return; }
                await Task.Delay(1000, token);
            }
            throw new InvalidOperationException("应用启动超时，请查看日志。");
        }
        catch (OperationCanceledException) { SetStatus("启动已取消", Color.DimGray); }
        catch (Exception ex) { Append("错误：" + ex.Message); SetStatus("启动失败", Color.Firebrick); MessageBox.Show(ex.Message, "启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error); }
        finally { if (!openButton.Enabled) startButton.Enabled = true; }
    }

    private Process StartBackend(string java, string jar)
    {
        var psi = new ProcessStartInfo(java) { WorkingDirectory = root, UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
        psi.ArgumentList.Add("-jar"); psi.ArgumentList.Add(jar);
        psi.Environment["LOCAL_STORAGE_ROOT"] = Path.Combine(root, "data", "uploads");
        var p = new Process { StartInfo = psi, EnableRaisingEvents = true };
        p.OutputDataReceived += (_, e) => { if (e.Data != null) Append(e.Data); }; p.ErrorDataReceived += (_, e) => { if (e.Data != null) Append(e.Data); };
        p.Exited += (_, _) => BeginInvoke(() => { if (!IsDisposed) { SetStatus("应用已停止", Color.DimGray); startButton.Enabled = true; openButton.Enabled = stopButton.Enabled = false; } });
        p.Start(); p.BeginOutputReadLine(); p.BeginErrorReadLine(); return p;
    }

    private async Task<string> EnsureDockerAsync(CancellationToken token)
    {
        var docker = FindExecutable("docker.exe", @"C:\Program Files\Docker\Docker\resources\bin\docker.exe") ?? throw new InvalidOperationException("未找到 Docker Desktop，请先安装并启动 Docker Desktop。");
        if (await DockerReadyAsync(docker, token)) return docker;
        var desktop = @"C:\Program Files\Docker\Docker\Docker Desktop.exe";
        if (!File.Exists(desktop)) throw new InvalidOperationException("Docker 引擎未运行，且未找到 Docker Desktop。");
        Append("正在启动 Docker Desktop…"); Process.Start(new ProcessStartInfo(desktop) { UseShellExecute = true });
        for (var i = 0; i < 90 && !token.IsCancellationRequested; i++) { await Task.Delay(1000, token); if (await DockerReadyAsync(docker, token)) return docker; }
        throw new InvalidOperationException("Docker Desktop 启动超时，请检查其运行状态。");
    }

    private async Task<bool> DockerReadyAsync(string docker, CancellationToken token) => await RunAsync(docker, ["version", "--format", "{{.Server.Version}}"], root, token, false) == 0;
    private async Task<int> RunAsync(string file, IEnumerable<string> args, string workingDirectory, CancellationToken token, bool echo = true)
    {
        var psi = new ProcessStartInfo(file) { WorkingDirectory = workingDirectory, UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var arg in args) psi.ArgumentList.Add(arg);
        using var p = new Process { StartInfo = psi }; p.Start();
        var stdout = p.StandardOutput.ReadToEndAsync(token); var stderr = p.StandardError.ReadToEndAsync(token); await p.WaitForExitAsync(token);
        var output = await stdout; var error = await stderr;if (echo) { if (!string.IsNullOrWhiteSpace(output)) Append(output.Trim()); if (!string.IsNullOrWhiteSpace(error)) Append(error.Trim()); }
        return p.ExitCode;
    }
    private async Task<int> RunStreamingAsync(string file, IEnumerable<string> args, string workingDirectory, CancellationToken token)
    {
        var psi = new ProcessStartInfo(file) { WorkingDirectory = workingDirectory, UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var arg in args) psi.ArgumentList.Add(arg);
        using var p = new Process { StartInfo = psi };
        p.OutputDataReceived += (_, e) => { if (!string.IsNullOrWhiteSpace(e.Data)) Append(e.Data); };
        p.ErrorDataReceived += (_, e) => { if (!string.IsNullOrWhiteSpace(e.Data)) Append(e.Data); };
        p.Start(); p.BeginOutputReadLine(); p.BeginErrorReadLine(); await p.WaitForExitAsync(token); return p.ExitCode;
    }

    private async Task StopDatabaseAsync()
    {
        try { var docker = FindExecutable("docker.exe", @"C:\Program Files\Docker\Docker\resources\bin\docker.exe"); if (docker == null) return;StopBackend();SetStatus("正在停止数据库…",Color.DarkOrange);await RunAsync(docker,["compose","-p","talent-platform","-f",Path.Combine(root,"docker-compose.yml"),"stop","mysql"],root,CancellationToken.None);SetStatus("平台已停止",Color.DimGray); }
        catch (Exception ex) { MessageBox.Show(ex.Message, "停止失败"); }
    }
    private void StopBackend(){startupCancellation?.Cancel();if (backend is { HasExited:false }) { try { backend.Kill(true); backend.WaitForExit(5000); } catch { } }backend?.Dispose();backend=null;openButton.Enabled=stopButton.Enabled=false;startButton.Enabled=true;SetStatus("应用已停止",Color.DimGray);}
    private async Task<bool> HealthyAsync(){try{using var r=await http.GetAsync("http://localhost:8080/actuator/health");return r.IsSuccessStatusCode&&(await r.Content.ReadAsStringAsync()).Contains("UP");}catch{return false;}}
    private static async Task<bool> PortInUseAsync(int port){try{using var c=new TcpClient();await c.ConnectAsync("127.0.0.1",port);return true;}catch{return false;}}
    private void OpenBrowser()=>Process.Start(new ProcessStartInfo("http://localhost:8080") { UseShellExecute=true });
    private void SetRunning(){SetStatus("运行中",Color.ForestGreen);startButton.Enabled=false;updateButton.Enabled=sourceRoot!=null;openButton.Enabled=stopButton.Enabled=true;}
    private void SetStatus(string text,Color color){if(InvokeRequired){BeginInvoke(()=>SetStatus(text,color));return;}statusLabel.Text=text;statusLabel.ForeColor=color;}
    private void Append(string text){if(InvokeRequired){BeginInvoke(()=>Append(text));return;}logs.AppendText($"[{DateTime.Now:HH:mm:ss}] {text}{Environment.NewLine}");}
    private string FindJar(){var choices=new[]{Path.Combine(root,"app","talent-platform.jar"),Path.Combine(root,"backend","target","talent-platform-0.1.0.jar")};return choices.FirstOrDefault(File.Exists)??throw new FileNotFoundException("未找到应用 JAR，请先执行发布构建。");}
    private string? FindJava(){var bundled=Path.Combine(root,"runtime","bin","javaw.exe");if(File.Exists(bundled))return bundled;var home=Environment.GetEnvironmentVariable("JAVA_HOME");if(!string.IsNullOrWhiteSpace(home)&&File.Exists(Path.Combine(home,"bin","javaw.exe")))return Path.Combine(home,"bin","javaw.exe");return FindExecutable("javaw.exe",@"C:\Program Files\Common Files\Oracle\Java\javapath\javaw.exe")??FindExecutable("java.exe",@"C:\Program Files\Common Files\Oracle\Java\javapath\java.exe");}
    private static string? FindExecutable(string name,params string[] known){foreach(var p in known)if(File.Exists(p))return p;foreach(var dir in (Environment.GetEnvironmentVariable("PATH")??"").Split(Path.PathSeparator,StringSplitOptions.RemoveEmptyEntries)){try{var p=Path.Combine(dir.Trim('"'),name);if(File.Exists(p))return p;}catch{}}return null;}
    private static string FindRoot(){var dir=new DirectoryInfo(AppContext.BaseDirectory);for(var i=0;i<7&&dir!=null;i++,dir=dir.Parent)if(File.Exists(Path.Combine(dir.FullName,"docker-compose.yml")))return dir.FullName;return AppContext.BaseDirectory;}
    private static string? FindSourceRoot(string start){var dir=new DirectoryInfo(start);for(var i=0;i<7&&dir!=null;i++,dir=dir.Parent)if(File.Exists(Path.Combine(dir.FullName,"backend","pom.xml"))&&File.Exists(Path.Combine(dir.FullName,"frontend","package.json")))return dir.FullName;return null;}
}
