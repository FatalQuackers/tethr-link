using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Pixlink.Services;

namespace Pixlink.Pages;

public sealed partial class SettingsPage : Page
{
    private bool _loading = true;
    private AppSettings S => App.Session.Settings;

    public SettingsPage()
    {
        InitializeComponent();

        PcNameBox.Text = App.Session.PcName;
        ThemeBox.SelectedIndex = S.Theme switch { "Light" => 1, "Dark" => 2, _ => 0 };
        StartupSwitch.IsOn = StartupTask.IsEnabled;

        ToastsSwitch.IsOn = S.ShowToasts;
        ContentSwitch.IsOn = S.ToastShowContent;
        SoundSwitch.IsOn = S.ToastSound;
        BuildAppsList();

        BatterySwitch.IsOn = S.LowBatteryAlert;
        BatterySlider.Value = S.LowBatteryLevel;
        BatterySlider.IsEnabled = S.LowBatteryAlert;
        HotspotSwitch.IsOn = S.AutoJoinHotspot;
        UpdateHotspotText();

        QualityBox.SelectedIndex = S.MirrorMaxSize switch { <= 1280 => 0, 1920 => 1, 1600 => 2, _ => 3 };
        FpsBox.SelectedIndex = S.MirrorFps switch { <= 30 => 0, <= 60 => 1, _ => 2 };
        BitrateSlider.Value = S.MirrorBitrateMbps;

        DownloadText.Text = $"Saved in {FileTransfers.DownloadFolder}";
        LoadLayout();
        VersionText.Text = $"Tethr Link {Updater.Current}" + (AppSettings.IsPortable ? " · Portable (settings are saved in the Data folder next to Pixlink.exe)" : "");
        AutoUpdateSwitch.IsOn = S.AutoCheckUpdates;
        FingerprintText.Text = $"This PC's certificate: {App.Session.PcFingerprint}";
        PhoneText.Text = $"Linked to {App.Session.PhoneName}";
        SyncSwitch.IsOn = S.SyncSettings;
        ShowAccount();
        App.Session.Account.Changed += OnAccountChanged;
        Unloaded += (_, _) => App.Session.Account.Changed -= OnAccountChanged;
        _loading = false;
    }

    // ---------- Account ----------

    private CancellationTokenSource? _signIn;

    private void OnAccountChanged() => DispatcherQueue.TryEnqueue(ShowAccount);

    private void ShowAccount()
    {
        var acct = App.Session.Account;
        SignedInPanel.Visibility = acct.SignedIn ? Visibility.Visible : Visibility.Collapsed;
        SignedOutPanel.Visibility = acct.SignedIn ? Visibility.Collapsed : Visibility.Visible;
        AccountText.Text = $"Signed in as {acct.Email ?? "your account"}";
        if (acct.SignedIn) SetAccountStatus(null);
    }

    private void SetAccountStatus(string? text)
    {
        AccountStatus.Text = text ?? "";
        AccountStatus.Visibility = text is null ? Visibility.Collapsed : Visibility.Visible;
    }

    private void SetBusy(bool busy)
    {
        ProviderButtons.IsHitTestVisible = !busy;
        ProviderButtons.Opacity = busy ? 0.5 : 1;
        EmailButton.IsEnabled = EmailBox.IsEnabled = !busy;
    }

    private async void OnProviderSignIn(object sender, RoutedEventArgs e)
    {
        var provider = (string)((FrameworkElement)sender).Tag;
        var name = provider switch { "github" => "GitHub", "discord" => "Discord", _ => "Google" };
        await RunSignIn($"Finish signing in with {name} in your browser…", ct => App.Session.Account.SignInWithProviderAsync(provider, ct));
    }

    private async void OnEmailSignIn(object sender, RoutedEventArgs e)
    {
        var email = EmailBox.Text.Trim();
        if (!email.Contains('@') || !email.Contains('.')) { SetAccountStatus("Type your email address first."); return; }
        await RunSignIn($"We emailed a sign-in link to {email}. Open it on this PC to finish.", ct => App.Session.Account.SignInWithEmailAsync(email, ct));
    }

    private async Task RunSignIn(string waiting, Func<CancellationToken, Task> signIn)
    {
        _signIn?.Cancel();
        _signIn = new CancellationTokenSource();
        SetBusy(true);
        SetAccountStatus(waiting);
        try { await signIn(_signIn.Token); }
        catch (OperationCanceledException) { SetAccountStatus("Sign-in timed out. Try again."); }
        catch (System.Net.HttpListenerException) { SetAccountStatus("Another app is using the sign-in port (47199). Close it and try again."); }
        catch (Exception ex) { SetAccountStatus($"Couldn't sign in: {ex.Message}"); }
        finally { SetBusy(false); }
    }

    private async void OnSignOut(object sender, RoutedEventArgs e) => await App.Session.SignOutAsync();

    private void OnSyncToggled(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        S.SyncSettings = SyncSwitch.IsOn;
        S.Save();
    }

    private void Save() { if (!_loading) S.Save(); }

    private void OnToggle(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        S.ShowToasts = ToastsSwitch.IsOn;
        S.ToastShowContent = ContentSwitch.IsOn;
        S.ToastSound = SoundSwitch.IsOn;
        S.LowBatteryAlert = BatterySwitch.IsOn;
        S.AutoJoinHotspot = HotspotSwitch.IsOn;
        S.AutoCheckUpdates = AutoUpdateSwitch.IsOn;
        if (S.StartWithWindows != StartupSwitch.IsOn)
        {
            S.StartWithWindows = StartupSwitch.IsOn;
            try { StartupTask.Set(StartupSwitch.IsOn); } catch (Exception ex) { Crash.Log(ex, "Start with Windows"); }
        }
        BatterySlider.IsEnabled = BatterySwitch.IsOn;
        Save();
    }

    private void OnPcNameChanged(object sender, RoutedEventArgs e)
    {
        var name = PcNameBox.Text.Trim();
        S.PcDisplayName = name.Length == 0 || name == Environment.MachineName ? null : name;
        Save();
    }

    private void OnThemeChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loading) return;
        S.Theme = (ThemeBox.SelectedItem as ComboBoxItem)?.Tag as string ?? "System";
        App.Window?.ApplyTheme();
        Save();
    }

    private void OnBatteryLevel(object sender, Microsoft.UI.Xaml.Controls.Primitives.RangeBaseValueChangedEventArgs e)
    {
        if (_loading) return;
        S.LowBatteryLevel = (int)BatterySlider.Value;
        Save();
    }

    private void OnMirrorChanged(object sender, object e)
    {
        if (_loading) return;
        if ((QualityBox.SelectedItem as ComboBoxItem)?.Tag is string q) S.MirrorMaxSize = int.Parse(q);
        if ((FpsBox.SelectedItem as ComboBoxItem)?.Tag is string f) S.MirrorFps = int.Parse(f);
        S.MirrorBitrateMbps = (int)BitrateSlider.Value;
        Save();
    }

    /// <summary>One switch per app that has sent a notification: off = no pop-ups from it.</summary>
    private void BuildAppsList()
    {
        var apps = App.Session.Notifications
            .GroupBy(n => n.App)
            .Select(g => (pkg: g.Key, name: g.First().AppName))
            .Concat(S.MutedApps.Select(pkg => (pkg, name: pkg)))
            .GroupBy(a => a.pkg).Select(g => g.First())
            .OrderBy(a => a.name)
            .ToList();

        AppsList.Children.Clear();
        foreach (var (pkg, name) in apps)
        {
            var toggle = new ToggleSwitch
            {
                IsOn = !S.MutedApps.Contains(pkg), OnContent = "", OffContent = "", MinWidth = 0,
                HorizontalAlignment = HorizontalAlignment.Right,
            };
            toggle.Toggled += (_, _) =>
            {
                if (toggle.IsOn) S.MutedApps.Remove(pkg); else S.MutedApps.Add(pkg);
                Save();
            };
            var row = new Grid();
            row.Children.Add(new TextBlock { Text = string.IsNullOrEmpty(name) ? pkg : name, VerticalAlignment = VerticalAlignment.Center });
            row.Children.Add(toggle);
            AppsList.Children.Add(row);
        }
    }

    private void UpdateHotspotText() =>
        HotspotText.Text = string.IsNullOrEmpty(App.Session.HotspotSsid)
            ? "Not set. You'll be asked the first time you use Hotspot."
            : $"Joins \"{App.Session.HotspotSsid}\" when you turn on Hotspot";

    private async void OnChangeHotspot(object sender, RoutedEventArgs e)
    {
        if (App.Window is { } w && await w.AskHotspotDetailsAsync()) UpdateHotspotText();
    }

    private async void OnOpenDownloads(object sender, RoutedEventArgs e) =>
        await Windows.System.Launcher.LaunchFolderPathAsync(FileTransfers.DownloadFolder);

    private async void OnOpenLogs(object sender, RoutedEventArgs e) =>
        await Windows.System.Launcher.LaunchFolderPathAsync(AppSettings.Folder);

    private async void OnCheckUpdates(object sender, RoutedEventArgs e)
    {
        CheckButton.IsEnabled = false;
        UpdateStatus.Text = "Checking…";
        try
        {
            var update = await Updater.CheckAsync();
            if (update is null) UpdateStatus.Text = "You're on the latest version.";
            else
            {
                UpdateStatus.Text = $"Tethr Link {update.Version} is available. Click Update now at the top of the window.";
                App.Window?.ShowUpdate(update);
            }
        }
        catch (Exception)
        {
            UpdateStatus.Text = "Couldn't check for updates. Are you online?";
        }
        CheckButton.IsEnabled = true;
    }

    /// <summary>
    /// Installed copies run the uninstaller (unins000.exe next to Pixlink.exe). Portable copies have nothing
    /// installed: deleting their folder removes everything, so the button opens it and quits.
    /// </summary>
    private async void OnUninstall(object sender, RoutedEventArgs e)
    {
        var uninstaller = Path.Combine(AppContext.BaseDirectory, "unins000.exe");
        var portable = AppSettings.IsPortable || !File.Exists(uninstaller);
        var deleteData = new CheckBox { Content = "Also delete my settings and phone pairing", IsChecked = true };
        var panel = new StackPanel { Spacing = 12 };
        panel.Children.Add(new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            Text = portable
                ? "This is a portable copy, so nothing is installed. Tethr Link will close and open its folder: delete the folder to remove it completely."
                : "Tethr Link will close and Windows will remove it from this PC." +
                  (App.Session.Account.SignedIn ? " This PC will also be taken out of your account." : ""),
        });
        if (!portable) panel.Children.Add(deleteData);
        var result = await new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "Uninstall Tethr Link?",
            Content = panel,
            PrimaryButtonText = portable ? "Close and open folder" : "Uninstall",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Close,
        }.ShowAsync();
        if (result != ContentDialogResult.Primary) return;

        // Leave the account tidy: a removed PC shouldn't show up on the phone's "Add from my account".
        if (App.Session.Account.SignedIn) { try { await App.Session.SignOutAsync(); } catch { } }

        try
        {
            if (portable)
            {
                System.Diagnostics.Process.Start("explorer.exe", $"\"{AppContext.BaseDirectory.TrimEnd('\\')}\"");
            }
            else
            {
                Directory.CreateDirectory(AppSettings.Folder);
                File.WriteAllText(Path.Combine(AppSettings.Folder, deleteData.IsChecked == true ? "delete-on-uninstall" : "keep-on-uninstall"), "");
                // The uninstaller closes Pixlink.exe itself (taskkill), so start it, then quit.
                System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(uninstaller) { UseShellExecute = true });
            }
        }
        catch (Exception ex) { Crash.Log(ex, "Uninstall"); return; }
        App.Window?.Quit();
    }

    private async void OnUnpair(object sender, RoutedEventArgs e)
    {
        var result = await new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = $"Unlink {App.Session.PhoneName}?",
            Content = "You'll need to scan a new QR code or enter a new code to link again.",
            PrimaryButtonText = "Unlink",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Close,
        }.ShowAsync();
        if (result == ContentDialogResult.Primary) App.Session.Unpair();
    }

    // ---------- Layout, clipboard, screen, help ----------
    private bool _loadingLayout;

    private void LoadLayout()
    {
        _loadingLayout = true;
        var st = App.Session.Settings;
        GroupSwitch.IsOn = st.GroupNotificationsByApp;
        TraySwitch.IsOn = st.CloseToTray;
        AutoClipSwitch.IsOn = st.AutoClipboard;
        CodesSwitch.IsOn = st.CopyCodes;
        StopHiddenSwitch.IsOn = st.StopMirrorWhenHidden;
        BluetoothSwitch.IsOn = st.AllowBluetooth;
        InternetSwitch.IsOn = st.AllowInternet;
        PhoneWakeSwitch.IsOn = st.AllowPhoneWake;
        NearbySwitch.IsOn = st.NearbyConnect;
        UpdatePhoneWakeStatus();
        QuickActionsHint.Text = string.Join(", ", st.QuickActions
            .Select(id => Pixlink.Models.QuickActionItem.Catalog.FirstOrDefault(c => c.Id == id).Name)
            .Where(n => n is not null));
        _loadingLayout = false;
    }

    private void OnLayoutToggled(object sender, RoutedEventArgs e)
    {
        if (_loadingLayout) return;
        var st = App.Session.Settings;
        st.GroupNotificationsByApp = GroupSwitch.IsOn;
        st.CloseToTray = TraySwitch.IsOn;
        st.AutoClipboard = AutoClipSwitch.IsOn;
        st.CopyCodes = CodesSwitch.IsOn;
        st.StopMirrorWhenHidden = StopHiddenSwitch.IsOn;
        st.Save();
        App.Window?.RefreshLayout();
    }

    private void OnInternetToggled(object sender, RoutedEventArgs e)
    {
        if (_loadingLayout) return;
        App.Session.Settings.AllowInternet = InternetSwitch.IsOn;
        App.Session.Settings.Save();
    }

    private async void OnBluetoothToggled(object sender, RoutedEventArgs e)
    {
        if (_loadingLayout) return;
        await App.Session.SetAllowBluetoothAsync(BluetoothSwitch.IsOn);
    }

    private void OnNearbyToggled(object sender, RoutedEventArgs e)
    {
        if (_loadingLayout) return;
        App.Session.SetNearbyConnect(NearbySwitch.IsOn);
    }

    private async void OnPhoneWakeToggled(object sender, RoutedEventArgs e)
    {
        if (_loadingLayout) return;
        App.Session.SetAllowPhoneWake(PhoneWakeSwitch.IsOn);
        await Task.Delay(1500); // let Bluetooth start, then say if it couldn't
        UpdatePhoneWakeStatus();
    }

    private void UpdatePhoneWakeStatus()
    {
        var unsupported = App.Session.Settings.AllowPhoneWake && App.Session.IPhoneBtState == Pixlink.Services.IPhoneBluetooth.LinkState.NotSupported;
        PhoneWakeStatus.Text = "This PC's Bluetooth can't do this (it needs Bluetooth 4.0+ with LE peripheral support). A USB Bluetooth adapter fixes it.";
        PhoneWakeStatus.Visibility = unsupported ? Visibility.Visible : Visibility.Collapsed;
    }

    private async void OnChooseQuickActions(object sender, RoutedEventArgs e)
    {
        var st = App.Session.Settings;
        var panel = new StackPanel { Spacing = 4, Width = 360 };
        panel.Children.Add(new TextBlock
        {
            Text = "Pick up to 9. The ones marked Power mode need Power mode on your phone.",
            TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 8),
        });
        var boxes = new List<(string Id, CheckBox Box)>();
        foreach (var c in Pixlink.Models.QuickActionItem.Catalog)
        {
            var box = new CheckBox { Content = c.NeedsPower ? $"{c.Name} (Power mode)" : c.Name, IsChecked = st.QuickActions.Contains(c.Id) };
            boxes.Add((c.Id, box));
            panel.Children.Add(box);
        }
        var result = await new ContentDialog
        {
            XamlRoot = XamlRoot, Title = "Quick actions", Content = new ScrollViewer { Content = panel, MaxHeight = 460 },
            PrimaryButtonText = "Save", CloseButtonText = "Cancel", DefaultButton = ContentDialogButton.Primary,
        }.ShowAsync();
        if (result != ContentDialogResult.Primary) return;
        // Keep the existing order, then add newly ticked ones at the end.
        var chosen = boxes.Where(b => b.Box.IsChecked == true).Select(b => b.Id).ToList();
        st.QuickActions = st.QuickActions.Where(chosen.Contains).Concat(chosen.Where(id => !st.QuickActions.Contains(id))).Take(9).ToList();
        st.Save();
        App.Session.RebuildQuickActions();
        LoadLayout();
    }

    private void OnViewLogs(object sender, RoutedEventArgs e) => Frame.Navigate(typeof(LogsPage));

    private void OnOpenUpdates(object sender, RoutedEventArgs e) => Frame.Navigate(typeof(UpdatesPage));

    private void OnRunSetup(object sender, RoutedEventArgs e)
    {
        App.Session.Settings.SetupDone = false;
        App.Session.Settings.Save();
        App.Window?.RunSetup();
    }
}
