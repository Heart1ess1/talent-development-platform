using System.Diagnostics;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;

namespace TalentPlatformLauncher;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        var executablePath = Environment.ProcessPath ?? AppContext.BaseDirectory;
        var instanceId = Convert.ToHexString(
            SHA256.HashData(Encoding.UTF8.GetBytes(Path.GetFullPath(executablePath))))[..12];
        using var mutex = new Mutex(
            true,
            $"TalentPlatformLauncher.Singleton.{instanceId}",
            out var first);
        if (!first)
        {
            MessageBox.Show("启动器已经在运行。", "人才培养平台");
            return;
        }

        ApplicationConfiguration.Initialize();
        Application.Run(new LauncherForm());
    }
}

internal sealed class LauncherForm : Form
{
    private enum RunMode
    {
        None,
        Release,
        Development,
        External
    }

    private sealed record ToolCommand(string FileName, params string[] PrefixArguments);

    private readonly Button releaseStartButton = new() { Text = "启动发布版", Width = 112, Height = 36 };
    private readonly Button developmentStartButton = new() { Text = "启动开发模式", Width = 126, Height = 36 };
    private readonly Button buildButton = new() { Text = "构建发布版", Width = 112, Height = 36 };
    private readonly Button openButton = new() { Text = "打开系统", Width = 100, Height = 36, Enabled = false };
    private readonly Button stopButton = new() { Text = "停止应用", Width = 100, Height = 36, Enabled = false };
    private readonly Button stopDatabaseButton = new() { Text = "停止数据库", Width = 110, Height = 36 };
    private readonly ToolStripStatusLabel statusLabel = new()
    {
        Text = "尚未启动",
        ForeColor = Color.DimGray,
        Font = new Font("Microsoft YaHei UI", 10, FontStyle.Bold)
    };
    private readonly ToolStripStatusLabel environmentLabel = new() { Spring = true, TextAlign = ContentAlignment.MiddleRight };
    private readonly TextBox logs = new()
    {
        Multiline = true,
        ReadOnly = true,
        ScrollBars = ScrollBars.Both,
        WordWrap = false,
        Dock = DockStyle.Fill,
        BackColor = Color.FromArgb(248, 249, 250),
        Font = new Font("Consolas", 9)
    };
    private readonly HttpClient http = new() { Timeout = TimeSpan.FromSeconds(2) };
    private readonly string root;
    private readonly string? sourceRoot;

    private Process? backendProcess;
    private Process? frontendProcess;
    private CancellationTokenSource? operationCancellation;
    private RunMode runMode;
    private string? activeUrl;
    private bool operationInProgress;
    private bool stoppingProcesses;

    public LauncherForm()
    {
        Text = "人才培养平台启动器";
        Width = 940;
        Height = 580;
        MinimumSize = new Size(780, 440);
        StartPosition = FormStartPosition.CenterScreen;

        root = FindRoot();
        sourceRoot = FindSourceRoot(root);
        developmentStartButton.Visible = sourceRoot != null;
        buildButton.Visible = sourceRoot != null;

        var toolbar = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            Height = 64,
            Padding = new Padding(12),
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = false
        };
        toolbar.Controls.AddRange(
        [
            releaseStartButton,
            developmentStartButton,
            buildButton,
            openButton,
            stopButton,
            stopDatabaseButton
        ]);

        var statusStrip = new StatusStrip();
        environmentLabel.Text = sourceRoot == null ? "发布环境" : "已检测到源码";
        statusStrip.Items.AddRange([statusLabel, environmentLabel]);

        Controls.Add(logs);
        Controls.Add(toolbar);
        Controls.Add(statusStrip);

        releaseStartButton.Click += async (_, _) => await StartReleaseAsync();
        developmentStartButton.Click += async (_, _) => await StartDevelopmentAsync();
        buildButton.Click += async (_, _) => await BuildReleaseAsync();
        openButton.Click += (_, _) => OpenBrowser();
        stopButton.Click += (_, _) => StopApplication();
        stopDatabaseButton.Click += async (_, _) => await StopDatabaseAsync();
        FormClosing += (_, _) =>
        {
            operationCancellation?.Cancel();
            StopManagedProcesses();
        };

        Append($"运行目录：{root}");
        Append(sourceRoot == null
            ? "未检测到项目源码，仅可运行发布版。"
            : $"源码目录：{sourceRoot}");
        UpdateButtons();
    }

    private async Task StartReleaseAsync()
    {
        if (operationInProgress || runMode != RunMode.None)
        {
            return;
        }

        BeginOperation("正在启动发布版…");
        var cancellation = operationCancellation!;
        try
        {
            if (await BackendHealthyAsync())
            {
                runMode = RunMode.External;
                activeUrl = "http://localhost:8080";
                SetStatus("检测到外部运行实例", Color.DarkOrange);
                Append("端口 8080 上已有健康的平台实例。启动器不会接管或停止该进程。");
                OpenBrowser();
                return;
            }

            await EnsurePortAvailableAsync(8080, "后端");
            var docker = await EnsureDockerAsync(cancellation.Token);
            await StartDatabaseAsync(docker, PreferSourceCompose: false, cancellation.Token);

            var jar = FindJar();
            var java = FindJava() ?? throw new InvalidOperationException(
                "未找到 Java 运行时。发布包应包含 runtime 目录，开发环境请安装 JDK 17 或更高版本。");

            SetStatus("正在启动发布版应用…", Color.DarkOrange);
            Append($"[发布版] {jar}");
            var process = StartManagedProcess(
                "发布版后端",
                new ToolCommand(java),
                ["-jar", jar],
                root,
                new Dictionary<string, string?>
                {
                    ["LOCAL_STORAGE_ROOT"] = Path.Combine(root, "data", "uploads")
                });
            backendProcess = process;

            await WaitForEndpointAsync(
                process,
                "http://localhost:8080/actuator/health",
                "发布版应用",
                cancellation.Token,
                requireUpStatus: true);

            runMode = RunMode.Release;
            activeUrl = "http://localhost:8080";
            SetStatus("发布版运行中", Color.ForestGreen);
            Append("发布版启动成功：http://localhost:8080");
            OpenBrowser();
        }
        catch (OperationCanceledException)
        {
            SetStatus("启动已取消", Color.DimGray);
        }
        catch (Exception ex)
        {
            StopManagedProcesses();
            ShowOperationError("发布版启动失败", ex);
        }
        finally
        {
            EndOperation(cancellation);
        }
    }

    private async Task StartDevelopmentAsync()
    {
        if (sourceRoot == null)
        {
            MessageBox.Show(
                "未找到 backend/pom.xml 和 frontend/package.json。可通过 TALENT_PLATFORM_SOURCE_ROOT 指定源码目录。",
                "无法启动开发模式",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
            return;
        }

        if (operationInProgress)
        {
            return;
        }

        if (runMode == RunMode.Release || runMode == RunMode.External)
        {
            MessageBox.Show("请先停止当前应用，再启动开发模式。", "应用正在运行");
            return;
        }

        if (runMode == RunMode.Development)
        {
            Append("正在重启开发模式…");
            StopManagedProcesses();
            runMode = RunMode.None;
            activeUrl = null;
        }

        BeginOperation("正在启动开发模式…");
        var cancellation = operationCancellation!;
        try
        {
            await EnsurePortAvailableAsync(8080, "开发后端");
            await EnsurePortAvailableAsync(5173, "Vite 前端");

            var maven = FindMaven() ?? throw new InvalidOperationException(
                "未找到 Maven。开发模式需要 JDK 17+ 和 Maven 3.9+，并将 mvn.cmd 加入 PATH。");
            var pnpm = FindPnpm() ?? throw new InvalidOperationException(
                "未找到 pnpm 或 Corepack。请安装 Node.js 24，并执行 corepack enable。");

            var docker = await EnsureDockerAsync(cancellation.Token);
            await StartDatabaseAsync(docker, PreferSourceCompose: true, cancellation.Token);

            SetStatus("正在检查前端依赖…", Color.DarkOrange);
            Append("[前端] 检查并同步 pnpm 依赖…");
            var installExit = await RunStreamingAsync(
                pnpm,
                ["install", "--frozen-lockfile", "--prefer-offline"],
                Path.Combine(sourceRoot, "frontend"),
                cancellation.Token,
                "前端依赖");
            if (installExit != 0)
            {
                throw new InvalidOperationException("前端依赖安装失败，请查看日志。");
            }

            SetStatus("正在启动源码服务…", Color.DarkOrange);
            var backend = StartManagedProcess(
                "开发后端",
                maven,
                ["spring-boot:run"],
                Path.Combine(sourceRoot, "backend"),
                new Dictionary<string, string?>
                {
                    ["LOCAL_STORAGE_ROOT"] = Path.Combine(sourceRoot, "data", "uploads")
                });
            backendProcess = backend;

            var frontend = StartManagedProcess(
                "Vite 前端",
                pnpm,
                ["dev", "--", "--host", "127.0.0.1", "--strictPort"],
                Path.Combine(sourceRoot, "frontend"));
            frontendProcess = frontend;

            await WaitForEndpointAsync(
                backend,
                "http://localhost:8080/actuator/health",
                "开发后端",
                cancellation.Token,
                requireUpStatus: true);
            await WaitForEndpointAsync(
                frontend,
                "http://localhost:5173",
                "Vite 前端",
                cancellation.Token);

            runMode = RunMode.Development;
            activeUrl = "http://localhost:5173";
            SetStatus("开发模式运行中", Color.ForestGreen);
            Append("开发模式启动成功：http://localhost:5173");
            Append("前端源码支持 Vite 热更新；Java 源码修改后可点击“重启开发模式”快速重启，无需打包 JAR。");
            OpenBrowser();
        }
        catch (OperationCanceledException)
        {
            SetStatus("开发模式启动已取消", Color.DimGray);
        }
        catch (Exception ex)
        {
            StopManagedProcesses();
            ShowOperationError("开发模式启动失败", ex);
        }
        finally
        {
            EndOperation(cancellation);
        }
    }

    private async Task BuildReleaseAsync()
    {
        if (sourceRoot == null)
        {
            MessageBox.Show("当前环境未检测到源码，无法构建发布版。", "无法构建");
            return;
        }

        if (operationInProgress || runMode != RunMode.None)
        {
            MessageBox.Show("请先停止正在运行的应用，再构建发布版。", "应用正在运行");
            return;
        }

        BeginOperation("正在构建发布版…");
        var cancellation = operationCancellation!;
        try
        {
            var maven = FindMaven() ?? throw new InvalidOperationException(
                "未找到 Maven。构建发布版需要 JDK 17+ 和 Maven 3.9+。");
            var pnpm = FindPnpm() ?? throw new InvalidOperationException(
                "未找到 pnpm 或 Corepack。请安装 Node.js 24，并执行 corepack enable。");

            SetStatus("正在构建前端…", Color.DarkOrange);
            var frontendDirectory = Path.Combine(sourceRoot, "frontend");
            var installExit = await RunStreamingAsync(
                pnpm,
                ["install", "--frozen-lockfile", "--prefer-offline"],
                frontendDirectory,
                cancellation.Token,
                "前端依赖");
            if (installExit != 0)
            {
                throw new InvalidOperationException("前端依赖安装失败，请查看日志。");
            }

            var frontendExit = await RunStreamingAsync(
                pnpm,
                ["run", "build"],
                frontendDirectory,
                cancellation.Token,
                "前端构建");
            if (frontendExit != 0)
            {
                throw new InvalidOperationException("前端构建失败，请查看日志。");
            }

            SetStatus("正在打包后端…", Color.DarkOrange);
            var backendExit = await RunStreamingAsync(
                maven,
                ["clean", "package", "-DskipTests"],
                Path.Combine(sourceRoot, "backend"),
                cancellation.Token,
                "后端构建");
            if (backendExit != 0)
            {
                throw new InvalidOperationException("后端打包失败，请查看日志。");
            }

            var builtJar = GetBuiltJarPath();
            if (!File.Exists(builtJar))
            {
                throw new FileNotFoundException("构建完成但未找到 JAR。", builtJar);
            }

            var deployedJar = GetDeploymentJarPath();
            if (!PathsEqual(builtJar, deployedJar))
            {
                Directory.CreateDirectory(Path.GetDirectoryName(deployedJar)!);
                File.Copy(builtJar, deployedJar, true);
                Append($"发布包 JAR 已更新：{deployedJar}");
            }
            else
            {
                Append($"JAR 构建完成：{builtJar}");
            }

            SetStatus("发布版构建完成", Color.ForestGreen);
            MessageBox.Show(
                "发布版构建完成。可点击“启动发布版”验证构建结果。",
                "构建完成",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
        }
        catch (OperationCanceledException)
        {
            SetStatus("构建已取消", Color.DimGray);
        }
        catch (Exception ex)
        {
            ShowOperationError("发布版构建失败", ex);
        }
        finally
        {
            EndOperation(cancellation);
        }
    }

    private async Task StartDatabaseAsync(
        string docker,
        bool PreferSourceCompose,
        CancellationToken token)
    {
        var compose = FindComposeFile(PreferSourceCompose);
        var composeDirectory = Path.GetDirectoryName(compose)!;

        SetStatus("正在准备数据库…", Color.DarkOrange);
        var imageExists = await RunAsync(
            new ToolCommand(docker),
            ["image", "inspect", "mysql:8.4"],
            composeDirectory,
            token,
            echo: false) == 0;

        if (!imageExists)
        {
            var imageArchive = FindDatabaseImageArchive();
            if (imageArchive != null)
            {
                Append($"本机缺少 mysql:8.4，正在导入随包镜像：{imageArchive}");
                var loadExit = await RunStreamingAsync(
                    new ToolCommand(docker),
                    ["load", "-i", imageArchive],
                    composeDirectory,
                    token,
                    "数据库镜像");
                if (loadExit != 0)
                {
                    throw new InvalidOperationException("随包 MySQL 镜像导入失败，请查看日志。");
                }
            }
            else
            {
                Append("本机缺少 mysql:8.4，Docker 将从已配置的镜像源下载。");
            }
        }

        SetStatus("正在启动并等待数据库就绪…", Color.DarkOrange);
        var composeExit = await RunStreamingAsync(
            new ToolCommand(docker),
            [
                "compose", "-p", "talent-platform", "-f", compose,
                "up", "-d", "--wait", "--wait-timeout", "90", "mysql"
            ],
            composeDirectory,
            token,
            "数据库");
        if (composeExit != 0)
        {
            var hint = imageExists
                ? string.Empty
                : " 本机原先没有 mysql:8.4；若日志显示 Docker Hub 连接失败，请配置中国镜像源，或在发布包 images 目录附带 mysql-8.4.tar。";
            throw new InvalidOperationException($"数据库启动失败，请查看日志。{hint}");
        }
    }

    private async Task StopDatabaseAsync()
    {
        if (operationInProgress)
        {
            return;
        }

        StopApplication();
        operationInProgress = true;
        UpdateButtons();
        try
        {
            var docker = FindExecutable(
                "docker.exe",
                @"C:\Program Files\Docker\Docker\resources\bin\docker.exe");
            if (docker == null)
            {
                throw new InvalidOperationException("未找到 Docker Desktop。");
            }

            var compose = FindComposeFile(PreferSourceCompose: sourceRoot != null);
            SetStatus("正在停止数据库…", Color.DarkOrange);
            await RunStreamingAsync(
                new ToolCommand(docker),
                ["compose", "-p", "talent-platform", "-f", compose, "stop", "mysql"],
                Path.GetDirectoryName(compose)!,
                CancellationToken.None,
                "数据库");
            SetStatus("应用和数据库已停止", Color.DimGray);
        }
        catch (Exception ex)
        {
            ShowOperationError("停止数据库失败", ex);
        }
        finally
        {
            operationInProgress = false;
            UpdateButtons();
        }
    }

    private void StopApplication()
    {
        operationCancellation?.Cancel();
        if (runMode == RunMode.External)
        {
            runMode = RunMode.None;
            activeUrl = null;
            SetStatus("已清除外部实例状态", Color.DimGray);
            Append("已清除外部实例状态；外部进程未被启动器停止。");
            UpdateButtons();
            return;
        }

        StopManagedProcesses();
        runMode = RunMode.None;
        activeUrl = null;
        SetStatus("应用已停止", Color.DimGray);
        Append("应用进程已停止，数据库继续运行。");
        UpdateButtons();
    }

    private void StopManagedProcesses()
    {
        stoppingProcesses = true;
        try
        {
            StopProcess(frontendProcess);
            StopProcess(backendProcess);
            frontendProcess = null;
            backendProcess = null;
        }
        finally
        {
            stoppingProcesses = false;
        }
    }

    private Process StartManagedProcess(
        string name,
        ToolCommand command,
        IEnumerable<string> arguments,
        string workingDirectory,
        IReadOnlyDictionary<string, string?>? environment = null)
    {
        var allArguments = command.PrefixArguments.Concat(arguments).ToArray();
        var psi = CreateProcessStartInfo(command.FileName, allArguments, workingDirectory);
        if (environment != null)
        {
            foreach (var pair in environment)
            {
                psi.Environment[pair.Key] = pair.Value;
            }
        }

        var process = new Process { StartInfo = psi, EnableRaisingEvents = true };
        process.OutputDataReceived += (_, e) =>
        {
            if (!string.IsNullOrWhiteSpace(e.Data))
            {
                Append($"[{name}] {e.Data}");
            }
        };
        process.ErrorDataReceived += (_, e) =>
        {
            if (!string.IsNullOrWhiteSpace(e.Data))
            {
                Append($"[{name}] {e.Data}");
            }
        };
        process.Exited += (_, _) =>
        {
            if (IsDisposed || Disposing)
            {
                return;
            }

            BeginInvoke(() => HandleManagedProcessExit(name, process));
        };

        Append($"正在启动{name}…");
        process.Start();
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        return process;
    }

    private void HandleManagedProcessExit(string name, Process process)
    {
        if (stoppingProcesses || operationInProgress || IsDisposed)
        {
            return;
        }

        var isCurrent = ReferenceEquals(process, backendProcess) || ReferenceEquals(process, frontendProcess);
        if (!isCurrent)
        {
            return;
        }

        var exitCode = TryGetExitCode(process);
        Append($"{name}已退出，退出码 {exitCode}。");
        StopManagedProcesses();
        runMode = RunMode.None;
        activeUrl = null;
        SetStatus($"{name}异常停止", Color.Firebrick);
        UpdateButtons();
    }

    private async Task<int> RunAsync(
        ToolCommand command,
        IEnumerable<string> arguments,
        string workingDirectory,
        CancellationToken token,
        bool echo = true)
    {
        var allArguments = command.PrefixArguments.Concat(arguments).ToArray();
        var psi = CreateProcessStartInfo(command.FileName, allArguments, workingDirectory);
        using var process = new Process { StartInfo = psi };
        process.Start();

        try
        {
            var stdout = process.StandardOutput.ReadToEndAsync(token);
            var stderr = process.StandardError.ReadToEndAsync(token);
            await process.WaitForExitAsync(token);
            var output = await stdout;
            var error = await stderr;
            if (echo)
            {
                if (!string.IsNullOrWhiteSpace(output))
                {
                    Append(output.Trim());
                }

                if (!string.IsNullOrWhiteSpace(error))
                {
                    Append(error.Trim());
                }
            }

            return process.ExitCode;
        }
        catch (OperationCanceledException)
        {
            StopProcess(process);
            throw;
        }
    }

    private async Task<int> RunStreamingAsync(
        ToolCommand command,
        IEnumerable<string> arguments,
        string workingDirectory,
        CancellationToken token,
        string logPrefix)
    {
        var allArguments = command.PrefixArguments.Concat(arguments).ToArray();
        var psi = CreateProcessStartInfo(command.FileName, allArguments, workingDirectory);
        using var process = new Process { StartInfo = psi };
        process.OutputDataReceived += (_, e) =>
        {
            if (!string.IsNullOrWhiteSpace(e.Data))
            {
                Append($"[{logPrefix}] {e.Data}");
            }
        };
        process.ErrorDataReceived += (_, e) =>
        {
            if (!string.IsNullOrWhiteSpace(e.Data))
            {
                Append($"[{logPrefix}] {e.Data}");
            }
        };

        process.Start();
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        try
        {
            await process.WaitForExitAsync(token);
            process.WaitForExit();
            return process.ExitCode;
        }
        catch (OperationCanceledException)
        {
            StopProcess(process);
            throw;
        }
    }

    private async Task<string> EnsureDockerAsync(CancellationToken token)
    {
        var docker = FindExecutable(
            "docker.exe",
            @"C:\Program Files\Docker\Docker\resources\bin\docker.exe")
            ?? throw new InvalidOperationException("未找到 Docker Desktop，请先安装 Docker Desktop。");

        if (await DockerReadyAsync(docker, token))
        {
            return docker;
        }

        var desktop = @"C:\Program Files\Docker\Docker\Docker Desktop.exe";
        if (!File.Exists(desktop))
        {
            throw new InvalidOperationException("Docker 引擎未运行，且未找到 Docker Desktop。");
        }

        Append("正在启动 Docker Desktop…");
        Process.Start(new ProcessStartInfo(desktop) { UseShellExecute = true });
        for (var i = 0; i < 90; i++)
        {
            await Task.Delay(1000, token);
            if (await DockerReadyAsync(docker, token))
            {
                return docker;
            }
        }

        throw new InvalidOperationException("Docker Desktop 启动超时，请检查其运行状态。");
    }

    private async Task<bool> DockerReadyAsync(string docker, CancellationToken token)
    {
        return await RunAsync(
            new ToolCommand(docker),
            ["version", "--format", "{{.Server.Version}}"],
            root,
            token,
            echo: false) == 0;
    }

    private async Task WaitForEndpointAsync(
        Process process,
        string url,
        string name,
        CancellationToken token,
        bool requireUpStatus = false)
    {
        for (var i = 0; i < 120; i++)
        {
            token.ThrowIfCancellationRequested();
            if (process.HasExited)
            {
                throw new InvalidOperationException($"{name}异常退出，退出码 {process.ExitCode}。");
            }

            if (await EndpointAvailableAsync(url, requireUpStatus))
            {
                return;
            }

            await Task.Delay(1000, token);
        }

        throw new InvalidOperationException($"{name}启动超时，请查看日志。");
    }

    private async Task<bool> BackendHealthyAsync()
    {
        return await EndpointAvailableAsync(
            "http://localhost:8080/actuator/health",
            requireUpStatus: true);
    }

    private async Task<bool> EndpointAvailableAsync(string url, bool requireUpStatus)
    {
        try
        {
            using var response = await http.GetAsync(url);
            if (!response.IsSuccessStatusCode)
            {
                return false;
            }

            return !requireUpStatus ||
                   (await response.Content.ReadAsStringAsync()).Contains(
                       "\"status\":\"UP\"",
                       StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            return false;
        }
    }

    private static async Task EnsurePortAvailableAsync(int port, string service)
    {
        if (await PortInUseAsync(port))
        {
            throw new InvalidOperationException(
                $"{service}端口 {port} 已被其他程序占用。请停止占用该端口的进程后重试。");
        }
    }

    private static async Task<bool> PortInUseAsync(int port)
    {
        try
        {
            using var client = new TcpClient();
            await client.ConnectAsync("127.0.0.1", port);
            return true;
        }
        catch
        {
            return false;
        }
    }

    private void BeginOperation(string status)
    {
        operationCancellation?.Dispose();
        operationCancellation = new CancellationTokenSource();
        operationInProgress = true;
        SetStatus(status, Color.DarkOrange);
        UpdateButtons();
    }

    private void EndOperation(CancellationTokenSource cancellation)
    {
        if (ReferenceEquals(operationCancellation, cancellation))
        {
            operationCancellation.Dispose();
            operationCancellation = null;
        }

        operationInProgress = false;
        UpdateButtons();
    }

    private void UpdateButtons()
    {
        if (InvokeRequired)
        {
            BeginInvoke(UpdateButtons);
            return;
        }

        var idle = !operationInProgress && runMode == RunMode.None;
        releaseStartButton.Enabled = idle && CanRunRelease();
        developmentStartButton.Enabled =
            !operationInProgress &&
            sourceRoot != null &&
            (runMode == RunMode.None || runMode == RunMode.Development);
        developmentStartButton.Text =
            runMode == RunMode.Development ? "重启开发模式" : "启动开发模式";
        buildButton.Enabled = idle && sourceRoot != null;
        openButton.Enabled = !operationInProgress && activeUrl != null;
        stopButton.Enabled =
            !operationInProgress &&
            (runMode == RunMode.External ||
             backendProcess is { HasExited: false } ||
             frontendProcess is { HasExited: false });
        stopButton.Text = runMode == RunMode.External ? "清除状态" : "停止应用";
        stopDatabaseButton.Enabled = !operationInProgress;
    }

    private bool CanRunRelease()
    {
        try
        {
            return File.Exists(FindJar());
        }
        catch
        {
            return false;
        }
    }

    private void OpenBrowser()
    {
        if (activeUrl == null)
        {
            return;
        }

        Process.Start(new ProcessStartInfo(activeUrl) { UseShellExecute = true });
    }

    private void SetStatus(string text, Color color)
    {
        if (InvokeRequired)
        {
            BeginInvoke(() => SetStatus(text, color));
            return;
        }

        statusLabel.Text = text;
        statusLabel.ForeColor = color;
    }

    private void Append(string text)
    {
        if (InvokeRequired)
        {
            BeginInvoke(() => Append(text));
            return;
        }

        logs.AppendText($"[{DateTime.Now:HH:mm:ss}] {text}{Environment.NewLine}");
    }

    private void ShowOperationError(string title, Exception exception)
    {
        Append($"{title}：{exception.Message}");
        SetStatus(title, Color.Firebrick);
        MessageBox.Show(
            exception.Message,
            title,
            MessageBoxButtons.OK,
            MessageBoxIcon.Error);
    }

    private string FindJar()
    {
        var choices = new List<string>
        {
            Path.Combine(root, "app", "talent-platform.jar")
        };
        if (sourceRoot != null)
        {
            choices.Add(GetBuiltJarPath());
        }

        return choices.FirstOrDefault(File.Exists)
            ?? throw new FileNotFoundException("未找到应用 JAR，请先执行“构建发布版”。");
    }

    private string GetBuiltJarPath()
    {
        return Path.Combine(
            sourceRoot ?? throw new InvalidOperationException("未检测到源码目录。"),
            "backend",
            "target",
            "talent-platform-0.1.0.jar");
    }

    private string GetDeploymentJarPath()
    {
        if (sourceRoot == null || !PathsEqual(root, sourceRoot))
        {
            return Path.Combine(root, "app", "talent-platform.jar");
        }

        return GetBuiltJarPath();
    }

    private string FindComposeFile(bool PreferSourceCompose)
    {
        var choices = new List<string>();
        if (PreferSourceCompose && sourceRoot != null)
        {
            choices.Add(Path.Combine(sourceRoot, "docker-compose.yml"));
        }

        choices.Add(Path.Combine(root, "docker-compose.yml"));
        if (!PreferSourceCompose && sourceRoot != null)
        {
            choices.Add(Path.Combine(sourceRoot, "docker-compose.yml"));
        }

        return choices.FirstOrDefault(File.Exists)
            ?? throw new FileNotFoundException("未找到 docker-compose.yml。");
    }

    private string? FindDatabaseImageArchive()
    {
        var choices = new List<string>
        {
            Path.Combine(root, "images", "mysql-8.4.tar")
        };
        if (sourceRoot != null)
        {
            choices.Add(Path.Combine(sourceRoot, "images", "mysql-8.4.tar"));
        }

        return choices.FirstOrDefault(File.Exists);
    }

    private string? FindJava()
    {
        var bundled = Path.Combine(root, "runtime", "bin", "javaw.exe");
        if (File.Exists(bundled))
        {
            return bundled;
        }

        var javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrWhiteSpace(javaHome))
        {
            var javaw = Path.Combine(javaHome, "bin", "javaw.exe");
            if (File.Exists(javaw))
            {
                return javaw;
            }

            var java = Path.Combine(javaHome, "bin", "java.exe");
            if (File.Exists(java))
            {
                return java;
            }
        }

        return FindExecutable(
                   "javaw.exe",
                   @"C:\Program Files\Common Files\Oracle\Java\javapath\javaw.exe")
               ?? FindExecutable(
                   "java.exe",
                   @"C:\Program Files\Common Files\Oracle\Java\javapath\java.exe");
    }

    private static ToolCommand? FindMaven()
    {
        var known = new List<string>();
        foreach (var variable in new[] { "MAVEN_HOME", "MVN_HOME" })
        {
            var value = Environment.GetEnvironmentVariable(variable);
            if (!string.IsNullOrWhiteSpace(value))
            {
                known.Add(Path.Combine(value, "bin", "mvn.cmd"));
            }
        }

        var executable = FindExecutable("mvn.cmd", known.ToArray())
                         ?? FindExecutable("mvn.exe", known.ToArray());
        return executable == null ? null : new ToolCommand(executable);
    }

    private static ToolCommand? FindPnpm()
    {
        var pnpm = FindExecutable("pnpm.cmd") ?? FindExecutable("pnpm.exe");
        if (pnpm != null)
        {
            return new ToolCommand(pnpm);
        }

        var corepack = FindExecutable(
            "corepack.cmd",
            @"C:\Program Files\nodejs\corepack.cmd");
        return corepack == null ? null : new ToolCommand(corepack, "pnpm");
    }

    private static ProcessStartInfo CreateProcessStartInfo(
        string file,
        IReadOnlyCollection<string> arguments,
        string workingDirectory)
    {
        ProcessStartInfo psi;
        if (file.EndsWith(".cmd", StringComparison.OrdinalIgnoreCase) ||
            file.EndsWith(".bat", StringComparison.OrdinalIgnoreCase))
        {
            var command = new StringBuilder("/d /s /c \"");
            command.Append(QuoteCmdArgument(file));
            foreach (var argument in arguments)
            {
                command.Append(' ').Append(QuoteCmdArgument(argument));
            }
            command.Append('"');

            psi = new ProcessStartInfo(
                Environment.GetEnvironmentVariable("ComSpec") ?? "cmd.exe")
            {
                // cmd.exe needs the complete /c command as a raw string. ArgumentList
                // escapes embedded quotes as \", which batch files treat literally.
                Arguments = command.ToString()
            };
        }
        else
        {
            psi = new ProcessStartInfo(file);
            foreach (var argument in arguments)
            {
                psi.ArgumentList.Add(argument);
            }
        }

        psi.WorkingDirectory = workingDirectory;
        psi.UseShellExecute = false;
        psi.CreateNoWindow = true;
        psi.RedirectStandardOutput = true;
        psi.RedirectStandardError = true;
        psi.StandardOutputEncoding = Encoding.UTF8;
        psi.StandardErrorEncoding = Encoding.UTF8;
        return psi;
    }

    private static string QuoteCmdArgument(string value)
    {
        return $"\"{value.Replace("\"", "\"\"")}\"";
    }

    private static void StopProcess(Process? process)
    {
        if (process == null)
        {
            return;
        }

        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
                process.WaitForExit(5000);
            }
        }
        catch
        {
            // The process may have exited between the state check and termination.
        }
        finally
        {
            process.Dispose();
        }
    }

    private static string TryGetExitCode(Process process)
    {
        try
        {
            return process.ExitCode.ToString();
        }
        catch
        {
            return "未知";
        }
    }

    private static string? FindExecutable(string name, params string[] knownPaths)
    {
        foreach (var path in knownPaths)
        {
            if (File.Exists(path))
            {
                return path;
            }
        }

        foreach (var directory in (Environment.GetEnvironmentVariable("PATH") ?? string.Empty)
                     .Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            try
            {
                var path = Path.Combine(directory.Trim('"'), name);
                if (File.Exists(path))
                {
                    return path;
                }
            }
            catch
            {
                // Ignore invalid PATH entries and continue searching.
            }
        }

        return null;
    }

    private static string FindRoot()
    {
        var configured = Environment.GetEnvironmentVariable("TALENT_PLATFORM_HOME");
        if (!string.IsNullOrWhiteSpace(configured) &&
            File.Exists(Path.Combine(configured, "docker-compose.yml")))
        {
            return Path.GetFullPath(configured);
        }

        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        for (var i = 0; i < 7 && directory != null; i++, directory = directory.Parent)
        {
            if (File.Exists(Path.Combine(directory.FullName, "docker-compose.yml")))
            {
                return directory.FullName;
            }
        }

        return AppContext.BaseDirectory;
    }

    private static string? FindSourceRoot(string start)
    {
        var configured = Environment.GetEnvironmentVariable("TALENT_PLATFORM_SOURCE_ROOT");
        if (IsSourceRoot(configured))
        {
            return Path.GetFullPath(configured!);
        }

        var directory = new DirectoryInfo(start);
        for (var i = 0; i < 7 && directory != null; i++, directory = directory.Parent)
        {
            if (IsSourceRoot(directory.FullName))
            {
                return directory.FullName;
            }
        }

        return null;
    }

    private static bool IsSourceRoot(string? path)
    {
        return !string.IsNullOrWhiteSpace(path) &&
               File.Exists(Path.Combine(path, "backend", "pom.xml")) &&
               File.Exists(Path.Combine(path, "frontend", "package.json"));
    }

    private static bool PathsEqual(string left, string right)
    {
        return string.Equals(
            Path.GetFullPath(left).TrimEnd(Path.DirectorySeparatorChar),
            Path.GetFullPath(right).TrimEnd(Path.DirectorySeparatorChar),
            StringComparison.OrdinalIgnoreCase);
    }
}
