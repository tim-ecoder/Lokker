# Lokker — Спецификация системного приложения

**Пакет:** `com.lokker.app`
**Целевая платформа:** LineageOS 22 · Android 15 · API 35
**Язык:** Java
**Тип:** Привилегированный системный APK (`/system/priv-app`)

---

## 1. Обзор функциональности

### Основные функции

- Скрытие любого установленного приложения из лаунчера
- **Скрытие самого Lokker из лаунчера** (переключатель в настройках — при включении Lokker доступен только через горячую клавишу)
- Защита паролем/биометрией при открытии Lokker
- Защита паролем при открытии скрытого приложения
- **Автоматическое повторное скрытие приложения при переключении пользователя** (Home, Назад, Недавние или открытие другого приложения)
- Удаление скрытых приложений с экрана недавних
- Подавление уведомлений от скрытых приложений
- **Графический интерфейс для добавления/удаления приложений** в список скрытых (выбор приложений с поиском)
- Секретная горячая клавиша для доступа к Lokker (**единственная** точка входа, когда Lokker сам скрыт)
- Секретная горячая клавиша для прямого открытия скрытого приложения
- Ввод секретного кода через номеронабиратель (`*#5655#`) — опциональный запасной вариант

### Не входит в объём проекта

- Шифрование файлов / SafeBox
- Галерея / файловый менеджер
- Облачное резервное копирование / синхронизация
- Многопользовательский режим / Рабочий профиль
- Удалённое стирание данных
- Изоляция сетевого трафика

---

## 2. Архитектура

Одномодульное Java-приложение. Паттерн MVVM-lite. Нет внешних зависимостей кроме AndroidX и Security Crypto. Четыре типа компонентов времени выполнения.

```
┌─────────────────────────────────────────────────────────────┐
│                      Lokker Process                         │
├──────────────┬──────────────┬──────────────┬───────────────┤
│   UI Layer   │  ViewModel   │   Services   │  BroadcastRx  │
│              │              │              │               │
│ MainActivity │LokkerViewModel│LokkerAccessSvc│ PackageMonitor│
│ AuthActivity │              │              │ BootReceiver  │
│ SetupActivity│              │              │ SecretCodeRx  │
│ SettingsAct  │              │              │ ScreenReceiver│
│ ChangePwAct  │              │              │               │
│ HotkeySetup  │              │              │               │
├──────────────┴──────────────┴──────────────┴───────────────┤
│                      Repository                             │
│              AppRepository (single source of truth)         │
├────────────────────────────┬────────────────────────────────┤
│       LokkerDatabase       │    EncryptedPreferences        │
│  Room: LokkerApp, HotkeyMap│  auth hash, self-hide,        │
│                            │  pendingRehide set             │
└────────────────────────────┴────────────────────────────────┘
```

> **NotificationListenerService не нужен.** `setApplicationHiddenSetting` полностью предотвращает запуск скрытых приложений — они не могут отправлять уведомления. См. Раздел 8.

### Ответственности компонентов

| Компонент | Тип | Ответственность |
|---|---|---|
| `LokkerAccessibilityService` | AccessibilityService | Обнаружение смены приложения на переднем плане через `TaskStackListener` + `TYPE_WINDOW_STATE_CHANGED`; **инициирование повторного скрытия при потере фокуса скрытым приложением**; удаление из списка недавних; обнаружение горячих клавиш |
| `PackageMonitor` | BroadcastReceiver | `PACKAGE_REPLACED`/`ADDED` — повторное применение состояния скрытия после обновлений (дополнительная страховка; система сохраняет состояние скрытия при обновлениях) |
| `BootReceiver` | BroadcastReceiver | `BOOT_COMPLETED` — проверка состояния всех скрытых приложений (дополнительная страховка; состояние сохраняется в packages.xml) |
| `ScreenReceiver` | BroadcastReceiver | `SCREEN_ON` — повторная проверка состояния всех скрытых приложений |
| `SecretCodeReceiver` | BroadcastReceiver | Номеронабиратель `*#5655#` → запуск AuthActivity (опционально — может не работать на всех прошивках) |
| `AppRepository` | Репозиторий | Единый интерфейс к Room DB + EncryptedSharedPreferences + `setApplicationHiddenSetting` |

---

## 3. Механизм скрытия приложений

Основное скрытие использует скрытый API `PackageManager.setApplicationHiddenSetting()` — системный API скрытия, предназначенный именно для этой цели. Это обеспечивает **полную невидимость**: приложение исчезает из Настройки → Приложения, `pm list packages`, всех запросов PackageManager и не может запускать никакие компоненты.

> **Почему не `setComponentEnabledSetting`?** Этот API отключает только отдельные активности лаунчера — приложение остаётся полностью видимым в Настройки → Приложения, статистике хранилища, статистике батареи, `adb shell pm list packages`, а также для других приложений с `QUERY_ALL_PACKAGES`. Он совершенно непригоден для настоящего скрытия.

### Сравнение API

| Аспект | `setComponentEnabledSetting` | `setApplicationHiddenSetting` |
|---|---|---|
| Скрыто из лаунчера | Да | Да |
| Скрыто из Настройки → Приложения | **НЕТ** | **ДА** |
| Скрыто из `pm list packages` | **НЕТ** | **ДА** |
| Скрыто от запросов других приложений | **НЕТ** | **ДА** |
| Предотвращает запуск приложения | **НЕТ** (сервисы/ресиверы продолжают работать) | **ДА** |
| Уведомления блокируются по умолчанию | **НЕТ** (нужен NotificationListenerService) | **ДА** (приложение не может запуститься) |
| Сохраняется после перезагрузки | Да | Да |
| Сохраняется после обновления приложения | Ненадёжно (нужен PackageMonitor) | Да (система поддерживает) |
| Данные сохраняются | Да | Да |
| Разрешение | `CHANGE_COMPONENT_ENABLED_STATE` | `MANAGE_USERS` (signature) |

### Разрешение

`setApplicationHiddenSetting()` требует `MANAGE_USERS` (signature|privileged). Поскольку Lokker собирается с `certificate: "platform"` в дереве AOSP, это разрешение предоставляется автоматически. Также необходимо включение в белый список в `privapp-permissions-lokker.xml`.

### addApplication(packageName)

Добавляет приложение в управляемый список Lokker. Приложение теперь защищено паролем — его можно открыть только через Lokker. Кэширует иконку/название, сохраняет в Room и создаёт закреплённый ярлык. **Не** скрывает приложение; для этого вызовите `hideApp()` отдельно.

```java
// AppRepository.java

public void addApplication(String packageName) {
    // Кэшируем название и иконку (запросы к PM работают, пока приложение ещё видимо)
    String label = getAppLabel(packageName);
    cacheAppIcon(packageName);

    // Сохраняем в Room — приложение теперь управляется Lokker
    LokkerApp record = new LokkerApp(
        packageName,
        label,
        null, // hotkeySequence
        false, // hidden
        System.currentTimeMillis()
    );
    db.lokkerAppDao().insert(record);

    // Автоматически создаём закреплённый ярлык на домашнем экране
    createPinnedShortcut(packageName);
}
```

### removeApplication(packageName)

Полностью удаляет приложение из Lokker. Снимает скрытие если скрыто, удаляет из Room, удаляет закреплённый ярлык, очищает кэшированную иконку. Приложение возвращается в нормальное состояние (больше не защищено паролем).

```java
// AppRepository.java

public void removeApplication(String packageName) {
    // Снимаем скрытие на системном уровне, если приложение сейчас скрыто
    pm.setApplicationHiddenSetting(packageName, false);

    // Удаляем из Room
    db.lokkerAppDao().delete(packageName);
    pendingRehide.remove(packageName);
    persistPendingRehide();

    // Удаляем закреплённый ярлык
    ShortcutManager sm = ctx.getSystemService(ShortcutManager.class);
    sm.disableShortcuts(List.of("lokker_" + packageName));

    // Очищаем кэшированную иконку
    new File(ctx.getFilesDir(), "icons/" + packageName + ".png").delete();
}
```

### hideApp(packageName)

Скрывает приложение, которое уже добавлено в Lokker. Приложение исчезает из лаунчера, настроек и всех запросов PM.

```java
public void hideApp(String packageName) {
    LokkerApp record = db.lokkerAppDao().get(packageName);
    if (record == null) return; // сначала должно быть добавлено

    pm.setApplicationHiddenSetting(packageName, true);
    db.lokkerAppDao().setHidden(packageName, true);
}
```

> **Примечание:** `setApplicationHiddenSetting` — это скрытый API (`@hide`). В сборках AOSP (Android.bp с `platform_apis: true`) он вызывается напрямую. При сборке против заглушек SDK используйте рефлексию:
> ```java
> Method m = PackageManager.class.getMethod(
>     "setApplicationHiddenSetting", String.class, boolean.class);
> m.invoke(pm, packageName, true);
> ```

### unhideApp(packageName)

Снимает скрытие с приложения, но оставляет его в списке Lokker (по-прежнему защищено паролем). Приложение снова появляется в лаунчере/настройках, но может быть открыто только через Lokker.

```java
public void unhideApp(String packageName) {
    LokkerApp record = db.lokkerAppDao().get(packageName);
    if (record == null) return;

    pm.setApplicationHiddenSetting(packageName, false);
    db.lokkerAppDao().setHidden(packageName, false);
}
```

### unhideTemporarily(packageName)

Вызывается перед запуском скрытого приложения через интерфейс Lokker. Снимает скрытие со всего приложения; `LokkerAccessibilityService` повторно скроет его при потере переднего плана.

```java
public boolean unhideTemporarily(String packageName) {
    LokkerApp record = db.lokkerAppDao().get(packageName);
    if (record == null || !record.hidden) return false;

    pm.setApplicationHiddenSetting(packageName, false);

    // Сохраняем состояние ожидания для переживания смерти процесса
    pendingRehide.add(packageName);
    persistPendingRehide();

    return true;
}
```

### Сохранение pendingRehide

Набор временно раскрытых приложений **должен** пережить смерть процесса. Если Lokker будет убит, пока приложение временно видимо, оно должно быть повторно скрыто при следующем запуске.

```java
// AppRepository.java

private Set<String> pendingRehide = new HashSet<>();

private void persistPendingRehide() {
    encryptedPrefs.edit()
        .putStringSet("pending_rehide", pendingRehide)
        .apply();
}

private void loadPendingRehide() {
    pendingRehide = new HashSet<>(
        encryptedPrefs.getStringSet("pending_rehide", Collections.emptySet())
    );
}

/** Вызывается из LokkerApp.onCreate() — повторно скрываем утёкшие приложения */
public void recoverLeakedApps() {
    loadPendingRehide();
    for (String pkg : new HashSet<>(pendingRehide)) {
        pm.setApplicationHiddenSetting(pkg, true);
        pendingRehide.remove(pkg);
    }
    persistPendingRehide();
}
```

### Оставшиеся утечки видимости

Даже с `setApplicationHiddenSetting` следующее невозможно предотвратить:

| Утечка | Примечания |
|---|---|
| `pm list packages -u` (ADB) | Показывает скрытые пакеты — требуется ADB/root, вне модели угроз |
| Файловая система `/data/app/` | APK по-прежнему на диске — требуется root |
| Статистика использования до скрытия | Можно очистить через `UsageStatsManager` с системным разрешением |
| Кратковременная видимость при временном раскрытии | Видно только пока пользователь активно использует приложение |

---

## 4. Повторное скрытие при переключении приложений

**Это критически важная поведенческая функция.** Когда скрытое приложение временно разблокировано и пользователь переключается (Home, Назад, Недавние или просто открывает другое приложение), скрытое приложение должно быть немедленно повторно скрыто и удалено из списка недавних.

### Обнаружение — двойной механизм

Две независимые системы обнаружения переднего плана обеспечивают надёжность:

#### Основной: TaskStackListener (скрытый API AOSP)

Более надёжен, чем AccessibilityService для обнаружения изменений задач/переднего плана. Доступен приложениям, подписанным платформенным сертификатом.

```java
// LokkerAccessibilityService.java — регистрируется при запуске сервиса

private void registerTaskStackListener() {
    IActivityTaskManager atm = ActivityTaskManager.getService();
    atm.registerTaskStackListener(new TaskStackListener() {
        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo info) {
            String pkg = info.baseActivity != null
                ? info.baseActivity.getPackageName() : null;
            handleForegroundChange(pkg);
        }
    });
}
```

#### Вспомогательный: AccessibilityService (запасной вариант)

```java
// LokkerAccessibilityService.java

private String currentForegroundPkg = null;

@Override
public void onAccessibilityEvent(AccessibilityEvent event) {
    if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

    CharSequence pkgSeq = event.getPackageName();
    if (pkgSeq == null) return;
    String pkg = pkgSeq.toString();

    if (!pkg.equals(currentForegroundPkg)) {
        handleForegroundChange(pkg);
    }
}
```

#### Общая логика повторного скрытия

```java
private void handleForegroundChange(String newPkg) {
    String prev = currentForegroundPkg;
    currentForegroundPkg = newPkg;

    if (prev != null && repo.isPendingRehide(prev)) {
        // Повторное скрытие на системном уровне — полная невидимость восстановлена
        repo.rehideApp(prev);
        // Удаление из списка недавних
        repo.removeFromRecents(prev);
    }
}
```

```java
// AppRepository.java
public void rehideApp(String packageName) {
    pm.setApplicationHiddenSetting(packageName, true);
    pendingRehide.remove(packageName);
    persistPendingRehide();
}
```

### Удаление из списка недавних (двойной механизм)

```java
// Механизм 1: Флаги при запуске
public void launchHiddenApp(String packageName) {
    // Необходимо снять скрытие перед getLaunchIntentForPackage (скрытые приложения возвращают null)
    unhideTemporarily(packageName);

    Intent intent = pm.getLaunchIntentForPackage(packageName);
    if (intent == null) return;
    intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
    ctx.startActivity(intent);
}

// Механизм 2: ActivityManager.removeTask() при потере фокуса
public void removeFromRecents(String packageName) {
    ActivityManager am = ctx.getSystemService(ActivityManager.class);
    List<ActivityManager.RecentTaskInfo> tasks = am.getRecentTasks(100, 0);
    for (ActivityManager.RecentTaskInfo task : tasks) {
        if (task.baseIntent.getComponent() != null
                && packageName.equals(task.baseIntent.getComponent().getPackageName())) {
            am.removeTask(task.id);
        }
    }
}
```

### Диаграмма потока повторного скрытия

```
Пользователь открывает скрытое приложение через Lokker
  → Аутентификация (биометрия/PIN)
  → unhideTemporarily() — setApplicationHiddenSetting(pkg, false)
  → pendingRehide сохранён в EncryptedPrefs
  → startActivity с флагами EXCLUDE_FROM_RECENTS
  → пользователь нормально использует приложение
  → пользователь нажимает Home / Назад / переключает приложение
  → TaskStackListener ИЛИ AccessibilityService обнаруживает смену переднего плана
  → предыдущий пакет был в pendingRehide
  → setApplicationHiddenSetting(pkg, true) — полное повторное скрытие на системном уровне
  → removeFromRecents() — очищает из списка задач
  → pendingRehide очищен и сохранён
  → готово — приложение снова полностью невидимо
```

### Восстановление после смерти процесса

Если Lokker убит, пока приложение временно раскрыто, `LokkerApp.onCreate()` вызывает `recoverLeakedApps()`, который сканирует сохранённый набор `pendingRehide` и немедленно повторно скрывает все утёкшие приложения (см. Раздел 3).

---

## 5. Самоскрытие и секретный вход

Lokker может скрыть себя из лаунчера через **переключатель** в Настройках. При самоскрытии иконка приложения полностью исчезает — **единственный** способ открыть Lokker — через настроенную горячую клавишу (по умолчанию: Громкость↑ Громкость↑ Громкость↓). Секретный код номеронабирателя (`*#5655#`) доступен как опциональный запасной вариант, но горячая клавиша является основной и рекомендуемой точкой входа.

### Псевдонимы в манифесте

Две записи Activity: реальная `MainActivity` (никогда не отключается, доступна через явный intent) и псевдоним `LokkerLauncher` (отключается при самоскрытии).

```xml
<!-- Реальная активность — всегда существует, никогда не отключается -->
<activity
    android:name=".ui.MainActivity"
    android:exported="true"
    android:excludeFromRecents="true"
    android:showWhenLocked="false">
    <intent-filter>
        <action android:name="com.lokker.app.OPEN"/>
        <category android:name="android.intent.category.DEFAULT"/>
    </intent-filter>
</activity>

<!-- Псевдоним для лаунчера — ЭТОТ отключается при самоскрытии -->
<activity-alias
    android:name=".LokkerLauncher"
    android:targetActivity=".ui.MainActivity"
    android:enabled="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
    </intent-filter>
</activity-alias>
```

### setSelfHidden()

Самоскрытие управляется переключателем в настройках. Перед включением система **должна** убедиться, что горячая клавиша Lokker настроена — иначе пользователь заблокирует сам себя.

```java
public void setSelfHidden(boolean hidden) {
    if (hidden) {
        // Защита: отказ от самоскрытия, если горячая клавиша не настроена
        HotkeyConfig config = repo.getHotkeyConfig();
        if (config.getLokkerHotkey() == null || config.getLokkerHotkey().isEmpty()) {
            throw new IllegalStateException("Cannot self-hide without a configured hotkey");
        }
    }

    ComponentName alias = new ComponentName(ctx, "com.lokker.app.LokkerLauncher");
    int state = hidden
        ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

    ctx.getPackageManager().setComponentEnabledSetting(
        alias, state, PackageManager.DONT_KILL_APP
    );
    prefs.edit().putBoolean("self_hidden", hidden).apply();
}
```

### Автоматическое раскрытие при переустановке

Когда Lokker сам переустанавливается (или обновляется), он **должен** сбросить видимость в лаунчере. При переустановке `setComponentEnabledSetting` сбрасывается к значению по умолчанию из манифеста (включён), поэтому псевдоним уже восстановлен. `PackageMonitor` обнаруживает `PACKAGE_REPLACED` для собственного пакета Lokker и очищает настройку `self_hidden` для поддержания согласованности состояния:

```java
// В PackageMonitor.onReceive():
if (packageName.equals(ctx.getPackageName())) {
    // Lokker был переустановлен/обновлён — убеждаемся, что он виден в лаунчере
    prefs.edit().putBoolean("self_hidden", false).apply();
    return; // не обрабатываем как скрытое приложение
}
```

Это предотвращает сценарий, когда настройка говорит «самоскрыт», но псевдоним уже был сброшен системой, и гарантирует, что пользователь всегда сможет найти Lokker в лаунчере после переустановки.

### Секретный вход через номеронабиратель

Регистрация `*#LOKK#` (`*#5655#`) как секретного кода:

```xml
<receiver android:name=".receiver.SecretCodeReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.provider.Telephony.SECRET_CODE"/>
        <data android:scheme="android_secret_code" android:host="5655"/>
    </intent-filter>
</receiver>
```

---

## 6. Уровень аутентификации

Двухуровневая аутентификация: **хеш пароля** (6-значный PIN или пароль для быстрой разблокировки) + **BiometricPrompt** (отпечаток пальца/лицо).

### Хранение пароля

Пароль хранится как хеш PBKDF2-HMAC-SHA256 в EncryptedSharedPreferences. (PBKDF2 выбран вместо Argon2 для избежания зависимости от нативной библиотеки в сборке AOSP.)

```java
// AuthManager.java

public void setPassword(String password) {
    byte[] salt = new byte[16];
    new SecureRandom().nextBytes(salt);

    SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
    KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 310000, 256);
    byte[] hash = factory.generateSecret(spec).getEncoded();

    String encoded = Base64.encodeToString(salt, Base64.NO_WRAP) + ":"
                   + Base64.encodeToString(hash, Base64.NO_WRAP);
    encryptedPrefs.edit().putString("pw_hash", encoded).apply();
}

public boolean verifyPassword(String input) {
    String stored = encryptedPrefs.getString("pw_hash", null);
    if (stored == null) return false;

    String[] parts = stored.split(":");
    byte[] salt = Base64.decode(parts[0], Base64.NO_WRAP);
    byte[] storedHash = Base64.decode(parts[1], Base64.NO_WRAP);

    SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
    KeySpec spec = new PBEKeySpec(input.toCharArray(), salt, 310000, 256);
    byte[] inputHash = factory.generateSecret(spec).getEncoded();

    return MessageDigest.isEqual(storedHash, inputHash);
}
```

### BiometricPrompt

```java
public void showBiometric(FragmentActivity activity, Runnable onSuccess, Runnable onFail) {
    BiometricPrompt prompt = new BiometricPrompt(activity,
        ContextCompat.getMainExecutor(activity),
        new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                onSuccess.run();
            }
            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                onFail.run();
            }
            @Override
            public void onAuthenticationFailed() {
                onFail.run();
            }
        }
    );

    BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
        .setTitle("Lokker")
        .setSubtitle("Аутентифицируйтесь для продолжения")
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
            | BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        .build();

    prompt.authenticate(info);
}
```

### Поток AuthActivity

1. **AuthActivity запускается** с intent extra `target_package` (если открывается скрытое приложение) или null (открывается Lokker)
2. **Показывается BiometricPrompt** — сначала отпечаток пальца/лицо
3. Биометрия не прошла/недоступна → показывается **ввод 6-значного PIN**
4. Аутентификация успешна + `target_package` задан → `unhideTemporarily()` → `launchHiddenApp()` → `finish()`
5. Аутентификация успешна + нет цели → `startActivity(MainActivity)` → `finish()`
6. Аутентификация неудачна 5 раз → **блокировка на 30 секунд**, счётчик в EncryptedPrefs

> **Минимальная длина PIN:** 6 цифр. Экран ввода показывает 6 точек-индикаторов.

> **Безопасность:** AuthActivity должна иметь `excludeFromRecents="true"` и `showWhenLocked="false"`.

---

## 7. Графический интерфейс — Экран управления приложениями

Основной интерфейс — `MainActivity`, который показывает две вкладки/секции:

### 7.1 Список скрытых приложений (вид по умолчанию)

- **Строка поиска** вверху — фильтрует список скрытых приложений по имени/пакету в реальном времени
- RecyclerView, показывающий все текущие скрытые приложения
- Каждая строка содержит: **иконка приложения** | **имя приложения** | **имя пакета** (приглушённо) | **кнопка ⋮** (контекстное меню)
- Нажатие на строку запускает приложение (аутентификация → раскрытие → запуск → повторное скрытие при переключении)
- Нажатие на кнопку **⋮** открывает контекстное меню с тремя пунктами:
  - **Назначить горячую клавишу** — открывает диалог записи горячей клавиши для данного приложения (рекордер с бейджами клавиш, кнопки «Сбросить» и «Сохранить»)
  - **Раскрыть** — снять скрытие (приложение остаётся в Lokker, но видимо в лаунчере)
  - **Удалить из Lokker** — полностью удалить приложение из управления Lokker (раскрытие + удаление из Room + удаление ярлыка + очистка иконки). Показывает диалог подтверждения.
- **Плавающая кнопка действия (FAB)** → открывает выбор приложений для добавления
- Пустое состояние: центрированное сообщение "Нет скрытых приложений. Нажмите +, чтобы скрыть приложение." (строка поиска скрыта, когда список пуст)

### 7.2 Диалог выбора приложений (добавление в список скрытых)

Вызывается нажатием FAB. Полноэкранный диалог или нижняя панель:

- **Строка поиска** вверху — фильтрует по имени приложения или имени пакета
- RecyclerView со всеми установленными пользовательскими приложениями (по умолчанию без системных)
- Переключатель: "Показать системные приложения" — включает системные приложения в список
- Каждая строка: **иконка приложения** | **имя приложения** | **имя пакета** | **чекбокс**
- Множественный выбор: пользователь может отметить несколько приложений сразу
- **Кнопка "Скрыть выбранные"** внизу — вызывает `hideApp()` для каждого выбранного приложения
- Уже скрытые приложения показываются с меткой "Скрыто" и не доступны для выбора

### 7.3 Экран настроек (доступен через иконку ⚙ на панели инструментов)

Отдельный экран с кнопкой «← Назад» и заголовком «Настройки». Элементы сгруппированы по секциям с заголовками:

#### Секция «Безопасность»

- **Переключатель «Скрыть Lokker из лаунчера»** — при включении иконка Lokker исчезает; доступен только через горячую клавишу. Подпись: "Доступ только через горячую клавишу". Показывает диалог подтверждения: "Lokker будет скрыт из лаунчера. Вы сможете открыть его только горячей клавишей (Громкость↑ Громкость↑ Громкость↓). Продолжить?"
- **Сменить пароль** — навигация на отдельный экран смены пароля (см. 7.6)
- **Переключатель «Биометрия»** — включить/выключить биометрическую аутентификацию. Подпись: "Отпечаток пальца или Face Unlock". По умолчанию включён.

#### Секция «Горячие клавиши»

- **Настройка горячих клавиш** — навигация на экран настройки горячих клавиш (см. 7.7). Подпись показывает текущую последовательность (например, "Громкость↑ Громкость↑ Громкость↓"). Должна быть настроена до включения самоскрытия.

#### Секция «Управление»

- **Переключатель «Раскрыть все / Скрыть все»** — одна настройка, переключающаяся между двумя состояниями:
  - Когда приложения скрыты: показывает **"Раскрыть все приложения"** с подписью "Временно показать все скрытые приложения". Подтверждение: "Это раскроет все N скрытых приложений. Закреплённые ярлыки сохранятся." Вызывает `unhideAll()`, который сохраняет снимок списка и затем раскрывает.
  - Когда снимок существует (приложения только что раскрыты): показывает **"Скрыть все приложения"** с подписью "Повторно скрыть ранее раскрытые приложения". Подтверждение: "Повторно скрыть все ранее скрытых приложений?" Вызывает `rehideAll()`, который повторно скрывает из снимка.
  - Не показывается, когда нет скрытых приложений и нет снимка.

#### Секция «О приложении»

- Центрированный текст: **Lokker**, версия `v1.0.0 · com.lokker.app`, платформа `LineageOS 22 · Android 15`

### 7.4 Сводка макета интерфейса

#### Главный экран (со скрытыми приложениями)

```
┌──────────────────────────────────┐
│ Lokker                       [⚙] │
├──────────────────────────────────┤
│ 🔍 Фильтр приложений...         │
├──────────────────────────────────┤
│  [W] WhatsApp                [⋮] │
│      com.whatsapp                │
│──────────────────────────────────│
│  [S] Signal                  [⋮] │
│      org.thoughtcrime.securesms  │
│──────────────────────────────────│
│  [T] Telegram                [⋮] │
│      org.telegram.messenger      │
│                                  │
│                          [+ FAB] │
└──────────────────────────────────┘

[⋮] = кнопка контекстного меню (→ Назначить горячую клавишу / Раскрыть / Удалить из Lokker)
[+ FAB] = открыть выбор приложений
Нажатие на строку = запуск приложения (аутентификация → раскрытие → запуск → повторное скрытие)
```

#### Главный экран (пустое состояние)

```
┌──────────────────────────────────┐
│ Lokker                       [⚙] │
├──────────────────────────────────┤
│                                  │
│         [visibility_off]         │
│    Нет скрытых приложений.       │
│    Нажмите +, чтобы скрыть       │
│    приложение.                   │
│                                  │
│                          [+ FAB] │
└──────────────────────────────────┘
```

#### Экран выбора приложений

```
┌──────────────────────────────────┐
│ [←]  Скрыть приложения           │
├──────────────────────────────────┤
│ 🔍 Поиск приложений...          │
│ [○] Показать системные прил.     │
├──────────────────────────────────┤
│  ☐  [W] WhatsApp    [Скрыто]    │  ← недоступно, opacity 0.5
│  ☐  [S] Signal      [Скрыто]    │
│  ☑  [C] Chrome                   │
│  ☐  [Y] YouTube                  │
│  ☐  [I] Instagram                │
│  ...                             │
├──────────────────────────────────┤
│  [ Скрыть 1 приложение ]         │  ← disabled → "Выберите приложения"
└──────────────────────────────────┘

Текст кнопки адаптируется: "Скрыть N приложение/приложения/приложений"
```

### 7.5 Экран смены пароля

Отдельный экран с кнопкой «← Назад» → возврат в настройки. Заголовок: «Сменить пароль».

Три поля ввода:
- **Текущий пароль** — ввод текущего PIN/пароля для подтверждения
- **Новый пароль** — ввод нового PIN/пароля
- **Подтвердите новый пароль** — повторный ввод для подтверждения

Кнопка **«Сохранить»** внизу экрана. При успехе — возврат в настройки + snackbar «Пароль успешно изменён».

```
┌──────────────────────────────────┐
│ [←]  Сменить пароль              │
├──────────────────────────────────┤
│                                  │
│  Текущий пароль                  │
│  ┌────────────────────────────┐  │
│  │ ••••••                     │  │
│  └────────────────────────────┘  │
│                                  │
│  Новый пароль                    │
│  ┌────────────────────────────┐  │
│  │ Введите новый пароль       │  │
│  └────────────────────────────┘  │
│                                  │
│  Подтвердите новый пароль        │
│  ┌────────────────────────────┐  │
│  │ Повторите новый пароль     │  │
│  └────────────────────────────┘  │
│                                  │
│  [        Сохранить            ] │
└──────────────────────────────────┘
```

### 7.6 Экран настройки горячих клавиш

Отдельный экран с кнопкой «← Назад» → возврат в настройки. Заголовок: «Горячие клавиши».

#### Секция «Открытие Lokker»

Виджет записи горячей клавиши:
- Показывает текущую последовательность в виде бейджей клавиш (например, `[Vol ↑] [Vol ↑] [Vol ↓]`)
- Нажатие запускает режим записи: появляется индикатор «● Нажимайте клавиши...» с пульсирующей красной точкой
- Подпись: "Нажмите для записи новой последовательности"
- По завершении записи — snackbar «Горячая клавиша сохранена»

#### Секция «Горячие клавиши приложений»

Список всех скрытых приложений с назначенными (или неназначенными) горячими клавишами:
- Каждый элемент: иконка клавиатуры | **имя приложения** | подпись (последовательность или «Не назначена») | стрелка →
- Нажатие открывает диалог записи горячей клавиши для конкретного приложения

#### Диалог записи горячей клавиши приложения

Вызывается из двух мест:
1. Контекстное меню ⋮ → «Назначить горячую клавишу» на главном экране
2. Нажатие на элемент приложения на экране настройки горячих клавиш

Bottom sheet диалог с заголовком (имя приложения), виджетом рекордера (бейджи клавиш + индикатор записи), кнопками «Сбросить» и «Сохранить». При сохранении — snackbar «Горячая клавиша для {имя} сохранена».

```
┌──────────────────────────────────┐
│ [←]  Горячие клавиши             │
├──────────────────────────────────┤
│ ОТКРЫТИЕ LOKKER                  │
│ ┌────────────────────────────┐   │
│ │  [Vol ↑] [Vol ↑] [Vol ↓]  │   │
│ │                            │   │
│ │  Нажмите для записи новой  │   │
│ │  последовательности        │   │
│ └────────────────────────────┘   │
│──────────────────────────────────│
│ ГОРЯЧИЕ КЛАВИШИ ПРИЛОЖЕНИЙ       │
│                                  │
│ ⌨ WhatsApp                   [→] │
│   Не назначена                   │
│ ⌨ Signal                     [→] │
│   Vol ↓ Vol ↓ Vol ↑              │
│ ⌨ Telegram                   [→] │
│   Не назначена                   │
└──────────────────────────────────┘
```

### 7.7 Закреплённые ярлыки для скрытых приложений

Lokker **автоматически создаёт закреплённый ярлык на домашнем экране** при добавлении приложения в Lokker через `addApplication()` и **автоматически удаляет его** при удалении приложения через `removeApplication()`. Каждый ярлык проходит через `AuthActivity`, поэтому нажатие на него запускает аутентификацию → раскрытие → запуск → повторное скрытие при переключении. Это также позволяет приложениям для переназначения клавиш (KeyMapper и др.) запускать скрытые приложения без патча фреймворка — переназначатель просто обращается к закреплённому ярлыку.

#### Автоматическое создание ярлыков

Вызывается автоматически из `addApplication()`.

```java
// AppRepository.java

public void createPinnedShortcut(String packageName) {
    LokkerApp record = db.lokkerAppDao().get(packageName);
    if (record == null) return;

    ShortcutManager sm = ctx.getSystemService(ShortcutManager.class);
    if (!sm.isRequestPinShortcutSupported()) return;

    // Создаём intent, проходящий через AuthActivity
    Intent target = new Intent(ctx, AuthActivity.class);
    target.setAction("com.lokker.app.LAUNCH_HIDDEN");
    target.putExtra("target_package", packageName);

    // Используем кэшированную иконку, сохранённую до скрытия
    Icon icon = loadCachedIcon(packageName);  // см. ниже
    if (icon == null) {
        icon = Icon.createWithResource(ctx, R.drawable.ic_launcher);
    }

    ShortcutInfo shortcut = new ShortcutInfo.Builder(ctx, "lokker_" + packageName)
        .setShortLabel(record.appLabel)
        .setIcon(icon)
        .setIntent(target)
        .build();

    sm.requestPinShortcut(shortcut, null);
}
```

#### Кэширование иконок приложений перед скрытием

Иконки приложений должны быть кэшированы **до** вызова `setApplicationHiddenSetting(true)`, потому что скрытые приложения невидимы для запросов `PackageManager`. Иконки хранятся как PNG-файлы во внутреннем хранилище Lokker.

```java
// AppRepository.java

private void cacheAppIcon(String packageName) {
    try {
        Drawable icon = pm.getApplicationIcon(packageName);
        Bitmap bmp = drawableToBitmap(icon);
        File file = new File(ctx.getFilesDir(), "icons/" + packageName + ".png");
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    } catch (PackageManager.NameNotFoundException ignored) {}
}

private Icon loadCachedIcon(String packageName) {
    File file = new File(ctx.getFilesDir(), "icons/" + packageName + ".png");
    if (!file.exists()) return null;
    return Icon.createWithBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));
}

private Bitmap drawableToBitmap(Drawable drawable) {
    if (drawable instanceof BitmapDrawable) {
        return ((BitmapDrawable) drawable).getBitmap();
    }
    Bitmap bmp = Bitmap.createBitmap(
        drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(),
        Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bmp);
    drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
    drawable.draw(canvas);
    return bmp;
}
```

Кэширование иконок и создание ярлыков выполняются в `addApplication()`. Удаление ярлыков и очистка иконок выполняются в `removeApplication()`. См. Раздел 3.

### unhideAll() / rehideAll()

Используются переключателем скрыть все/раскрыть все в Настройках. Оперируют только флагом `hidden` — приложения остаются в управляемом списке Lokker.

```java
public void unhideAll() {
    List<LokkerApp> hiddenApps = db.lokkerAppDao().getAllHidden();
    if (hiddenApps.isEmpty()) return;

    // Сохраняем снимок имён пакетов для последующего повторного скрытия
    saveUnhideAllSnapshot(hiddenApps);

    for (LokkerApp app : hiddenApps) {
        pm.setApplicationHiddenSetting(app.packageName, false);
        db.lokkerAppDao().setHidden(app.packageName, false);
        // Закреплённые ярлыки и кэшированные иконки остаются на месте
    }

    pendingRehide.clear();
    persistPendingRehide();
}

public void rehideAll() {
    List<String> snapshot = loadUnhideAllSnapshot();
    if (snapshot == null || snapshot.isEmpty()) return;

    for (String pkg : snapshot) {
        LokkerApp record = db.lokkerAppDao().get(pkg);
        if (record == null) continue; // было удалено из Lokker

        try {
            pm.getPackageInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            continue; // приложение было удалено
        }

        pm.setApplicationHiddenSetting(pkg, true);
        db.lokkerAppDao().setHidden(pkg, true);
    }

    clearUnhideAllSnapshot();
}

// --- Сохранение снимка (EncryptedSharedPreferences) ---

private void saveUnhideAllSnapshot(List<LokkerApp> apps) {
    JSONArray arr = new JSONArray();
    for (LokkerApp app : apps) {
        arr.put(app.packageName);
    }
    prefs.edit().putString("unhide_all_snapshot", arr.toString()).apply();
}

private List<String> loadUnhideAllSnapshot() {
    String json = prefs.getString("unhide_all_snapshot", null);
    if (json == null) return null;

    List<String> result = new ArrayList<>();
    JSONArray arr = new JSONArray(json);
    for (int i = 0; i < arr.length(); i++) {
        result.add(arr.getString(i));
    }
    return result;
}

private void clearUnhideAllSnapshot() {
    prefs.edit().remove("unhide_all_snapshot").apply();
}

public boolean hasUnhideAllSnapshot() {
    return prefs.getString("unhide_all_snapshot", null) != null;
}
```

#### Обработка в AuthActivity

`AuthActivity` уже поддерживает extras `target_package` (Раздел 6). Intent ярлыка использует действие `com.lokker.app.LAUNCH_HIDDEN` для отличия от других точек входа, но поток аутентификации и запуска идентичен.

#### Добавление в манифест

```xml
<!-- AuthActivity должна принимать действие ярлыка -->
<activity android:name=".ui.AuthActivity"
    android:excludeFromRecents="true"
    android:showWhenLocked="true">
    <intent-filter>
        <action android:name="com.lokker.app.LAUNCH_HIDDEN"/>
        <category android:name="android.intent.category.DEFAULT"/>
    </intent-filter>
</activity>
```

#### Интеграция с переназначателем клавиш

Пользователь настраивает переназначатель клавиш на запуск закреплённого ярлыка (или явного intent'а `com.lokker.app.LAUNCH_HIDDEN` с extra `target_package`). Никакой прокси-активности, никакого патча фреймворка. Переназначатель запускает стандартный поток аутентификации Lokker.

#### Интерфейс в списке скрытых приложений

Контекстное меню по нажатию на кнопку ⋮ (ярлыки создаются/удаляются автоматически, запуск — по нажатию на строку):

```
┌──────────────────────────────────┐
│  ⌨ Назначить горячую клавишу     │
│  👁 Раскрыть                     │
│  🗑 Удалить из Lokker            │
└──────────────────────────────────┘
```

---

## 8. Подавление уведомлений

**Не требуется.** При использовании `setApplicationHiddenSetting` скрытые приложения не могут запускать какие-либо компоненты — ни сервисы, ни ресиверы, ни будильники. Они **не могут отправлять уведомления**. Система блокирует все запуски компонентов для скрытых пакетов на уровне `PackageManagerService`.

> **Примечание:** Во время кратковременного окна временного раскрытия (пока пользователь активно использует скрытое приложение) приложение МОЖЕТ отправлять уведомления. Это допустимо, поскольку пользователь активно использует приложение. Когда приложение повторно скрывается через `setApplicationHiddenSetting(pkg, true)`, приложение принудительно останавливается, и все ожидающие уведомления очищаются системой.

`LokkerNotificationListener` удалён из архитектуры. Разрешение `NotificationListenerService` не требуется.

---

## 9. Система горячих клавиш

Обнаружение горячих клавиш выполняется внутри `LokkerAccessibilityService` через `onKeyEvent()`:

```java
// LokkerAccessibilityService.java

private List<Integer> hotkeySequence = new ArrayList<>();
private long lastKeyTime = 0L;
private static final long SEQ_TIMEOUT = 1500L;

@Override
protected boolean onKeyEvent(KeyEvent event) {
    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

    long now = SystemClock.elapsedRealtime();
    if (now - lastKeyTime > SEQ_TIMEOUT) hotkeySequence.clear();
    lastKeyTime = now;

    hotkeySequence.add(event.getKeyCode());

    HotkeyConfig config = repo.getHotkeyConfig();

    // Горячая клавиша открытия Lokker (например, Громкость↑ Громкость↑ Громкость↓)
    if (config.getLokkerHotkey() != null && endsWith(hotkeySequence, config.getLokkerHotkey())) {
        hotkeySequence.clear();
        launchAuth(null);
        return true;
    }

    // Горячие клавиши для отдельных приложений
    for (Map.Entry<String, List<Integer>> entry : config.getAppHotkeys().entrySet()) {
        if (endsWith(hotkeySequence, entry.getValue())) {
            hotkeySequence.clear();
            launchAuth(entry.getKey());
            return true;
        }
    }

    return false;
}

private void launchAuth(String targetPackage) {
    Intent intent = new Intent(this, AuthActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    if (targetPackage != null) {
        intent.putExtra("target_package", targetPackage);
    }
    startActivity(intent);
}
```

| Тип горячей клавиши | По умолчанию | Настраиваемая | Поддерживаемые клавиши |
|---|---|---|---|
| Открыть Lokker | Громкость↑ Громкость↑ Громкость↓ | Да | Громкость, Питание (длительное), Камера |
| Открыть скрытое приложение N | Нет | Да, для каждого приложения | Те же |
| Код набора номера | `*#5655#` | Да | USSD-стиль `*#XXXX#` |

> **Предупреждение:** Клавиши громкости — самые безопасные. Длительное нажатие кнопки питания вызывает системное меню выключения на Android 12+. В конфигурации сервиса доступности требуется `canRequestFilterKeyEvents="true"`.

---

## 10. Слой данных

### База данных Room

```java
// LokkerApp.java — Сущность
// Представляет приложение, управляемое Lokker. Может находиться в скрытом или видимом состоянии.
// При добавлении в Lokker приложение требует пароль Lokker для открытия.
// В скрытом состоянии оно также невидимо на системном уровне.
@Entity(tableName = "lokker_apps")
public class LokkerApp {
    @PrimaryKey @NonNull
    public String packageName;
    public String appLabel;
    @TypeConverters(Converters.class)
    public List<Integer> hotkeySequence; // nullable, горячая клавиша для конкретного приложения
    public boolean hidden;               // true = скрыто на системном уровне
    public long addedAt;
}

// HotkeyMap.java — Сущность
@Entity(tableName = "hotkey_map")
public class HotkeyMap {
    @PrimaryKey
    public int id = 1;
    @TypeConverters(Converters.class)
    public List<Integer> lokkerHotkey;
}

// LokkerAppDao.java
@Dao
public interface LokkerAppDao {
    @Query("SELECT * FROM lokker_apps ORDER BY addedAt DESC")
    LiveData<List<LokkerApp>> getAllLive();

    @Query("SELECT * FROM lokker_apps")
    List<LokkerApp> getAll();

    @Query("SELECT * FROM lokker_apps WHERE packageName = :pkg")
    LokkerApp get(String pkg);

    @Query("SELECT COUNT(*) > 0 FROM lokker_apps WHERE packageName = :pkg")
    boolean isManaged(String pkg);

    @Query("SELECT COUNT(*) > 0 FROM lokker_apps WHERE packageName = :pkg AND hidden = 1")
    boolean isHidden(String pkg);

    @Query("SELECT * FROM lokker_apps WHERE hidden = 1")
    List<LokkerApp> getAllHidden();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(LokkerApp app);

    @Query("UPDATE lokker_apps SET hidden = :hidden WHERE packageName = :pkg")
    void setHidden(String pkg, boolean hidden);

    @Query("DELETE FROM lokker_apps WHERE packageName = :pkg")
    void delete(String pkg);

    @Query("DELETE FROM lokker_apps")
    void deleteAll();
}
```

### EncryptedSharedPreferences

```java
// LokkerPrefs.java
public class LokkerPrefs {
    private static SharedPreferences prefs;

    public static SharedPreferences get(Context ctx) {
        if (prefs == null) {
            MasterKey masterKey = new MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();

            prefs = EncryptedSharedPreferences.create(
                ctx,
                "lokker_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        }
        return prefs;
    }

    // Ключи:
    // "pw_hash"          — PBKDF2-хеш пароля пользователя
    // "self_hidden"      — boolean
    // "fail_count"       — int (счётчик неудачных попыток аутентификации)
    // "lockout_until"    — long (временная метка)
    // "pending_rehide"    — StringSet (пакеты, временно раскрытые)
    // "unhide_all_snapshot" — JSON-массив ранее скрытых приложений (для повторного скрытия всех)
    // ПРИМЕЧАНИЕ: "notif_auto_granted" удалён — NLS больше не нужен
}
```

---

## 11. Система сборки (AOSP/LineageOS)

### Android.bp

```
android_app {
    name: "Lokker",
    srcs: ["src/**/*.java"],
    privileged: true,
    certificate: "platform",
    platform_apis: true,
    sdk_version: "",
    min_sdk_version: "35",
    static_libs: [
        "androidx.core_core",
        "androidx.room_room-runtime",
        "androidx.security_security-crypto",
        "androidx.biometric_biometric",
        "androidx.lifecycle_lifecycle-runtime",
        "androidx.lifecycle_lifecycle-viewmodel",
        "androidx.appcompat_appcompat",
        "androidx.recyclerview_recyclerview",
        "com.google.android.material_material",
    ],
    plugins: ["androidx.room_room-compiler-plugin"],
    optimize: { enabled: false },
    resource_dirs: ["res"],
}
```

### privapp-permissions-lokker.xml

```xml
<permissions>
    <privapp-permissions package="com.lokker.app">
        <permission name="android.permission.MANAGE_USERS"/>
        <permission name="android.permission.CHANGE_COMPONENT_ENABLED_STATE"/>
        <permission name="android.permission.REMOVE_TASKS"/>
        <permission name="android.permission.GET_TASKS"/>
        <permission name="android.permission.WRITE_SECURE_SETTINGS"/>
        <permission name="android.permission.REAL_GET_TASKS"/>
        <permission name="android.permission.MANAGE_ACTIVITY_STACKS"/>
    </privapp-permissions>
</permissions>
```

> **Примечание:** `MANAGE_USERS` — ключевое разрешение для `setApplicationHiddenSetting()`. `CHANGE_COMPONENT_ENABLED_STATE` сохранено только для самоскрытия Lokker через activity-alias (Раздел 5).

### Интеграция в device.mk

```makefile
PRODUCT_PACKAGES += Lokker

PRODUCT_COPY_FILES += \
    packages/apps/Lokker/privapp-permissions-lokker.xml:\
    $(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-lokker.xml
```

---

## 12. Структура проекта

```
packages/apps/Lokker/
├── Android.bp
├── AndroidManifest.xml
├── privapp-permissions-lokker.xml
├── SPEC.md
├── res/
│   ├── drawable/
│   │   └── ic_launcher.xml
│   ├── layout/
│   │   ├── activity_main.xml          # строка поиска + список скрытых приложений + FAB
│   │   ├── activity_auth.xml          # экран ввода PIN-кода
│   │   ├── activity_setup.xml         # первоначальная настройка пароля
│   │   ├── activity_settings.xml      # экран настроек с секциями
│   │   ├── activity_change_pw.xml     # экран смены пароля (3 поля ввода)
│   │   ├── activity_hotkey_setup.xml  # запись горячих клавиш + список приложений
│   │   ├── dialog_app_picker.xml      # полноэкранный выбор приложений
│   │   ├── item_hidden_app.xml        # строка в списке скрытых приложений (с кнопкой ⋮)
│   │   └── item_picker_app.xml        # строка в списке выбора приложений
│   ├── values/
│   │   ├── strings.xml
│   │   ├── styles.xml
│   │   └── colors.xml
│   └── xml/
│       └── accessibility_service_config.xml
└── src/
    └── com/lokker/app/
        ├── LokkerApp.java                 # Подкласс Application
        ├── receiver/
        │   ├── BootReceiver.java           # BOOT_COMPLETED → повторное применение скрытого состояния
        │   ├── PackageMonitor.java         # PACKAGE_REPLACED → повторное скрытие
        │   ├── SecretCodeReceiver.java     # код набора *#5655# → открытие аутентификации
        │   └── ScreenReceiver.java         # SCREEN_ON → повторная проверка состояний
        ├── service/
        │   └── LokkerAccessibilityService.java   # мониторинг переднего плана + повторное скрытие + горячие клавиши + TaskStackListener
        ├── ui/
        │   ├── MainActivity.java           # список скрытых приложений, FAB, кнопка настроек
        │   ├── AuthActivity.java           # шлюз PIN + биометрия
        │   ├── SetupActivity.java          # первоначальная настройка пароля
        │   ├── SettingsActivity.java        # настройки (секции: безопасность, горячие клавиши, управление)
        │   ├── ChangePasswordActivity.java  # экран смены пароля
        │   ├── HotkeySetupActivity.java    # запись последовательностей клавиш + горячие клавиши приложений
        │   ├── AppPickerDialog.java        # выбор приложений с поиском (добавление в скрытые)
        │   ├── LokkerViewModel.java        # LiveData для списка скрытых приложений + фильтр поиска
        │   └── adapter/
        │       ├── LokkerAppsAdapter.java  # адаптер RecyclerView для основного списка
        │       └── AppPickerAdapter.java   # адаптер RecyclerView для списка выбора
        ├── domain/
        │   ├── AppRepository.java          # единый источник истины
        │   ├── AuthManager.java            # пароль + биометрия
        │   └── HotkeyManager.java          # сопоставление последовательностей
        └── data/
            ├── db/
            │   ├── LokkerDatabase.java     # база данных Room
            │   ├── LokkerApp.java          # сущность
            │   ├── HotkeyMap.java          # сущность
            │   ├── LokkerAppDao.java       # DAO
            │   └── Converters.java         # TypeConverters (JSON-списки)
            └── LokkerPrefs.java            # EncryptedSharedPreferences
```

---

## 13. Матрица поведения

| Сценарий | Ожидаемое поведение | Механизм |
|---|---|---|
| Пользователь открывает лаунчер | Иконка скрытого приложения не видна | `setApplicationHiddenSetting(pkg, true)` |
| Пользователь открывает Настройки → Приложения | **Скрытое приложение НЕ отображается в списке** | `setApplicationHiddenSetting` — полное скрытие на системном уровне |
| Пользователь выполняет `pm list packages` | **Скрытое приложение НЕ отображается в списке** | То же (скрыто от всех запросов PM) |
| Пользователь включает самоскрытие в настройках | Иконка Lokker исчезает из лаунчера | `setComponentEnabledSetting` отключает alias `LokkerLauncher` |
| Пользователь пытается найти Lokker (самоскрытый) | Lokker нигде не виден — ни в лаунчере, ни в недавних, ни в списке приложений в настройках | Alias отключён + `excludeFromRecents` |
| Пользователь нажимает горячую клавишу (единственный способ войти) | Запрос аутентификации → открывается интерфейс Lokker | Перехват `onKeyEvent` → `AuthActivity` → `MainActivity` |
| Пользователь запускает скрытое приложение через Lokker | Аутентификация → приложение запускается нормально | `setApplicationHiddenSetting(false)` → `startActivity` → повторное скрытие при переключении |
| **Пользователь переключается с скрытого приложения** | **Приложение немедленно скрывается, удаляется из недавних** | **`TaskStackListener` + `TYPE_WINDOW_STATE_CHANGED` → `setApplicationHiddenSetting(true)` + `removeTask()`** |
| Пользователь нажимает Home в скрытом приложении | Приложение скрывается, удаляется из недавних | То же, что выше |
| Пользователь нажимает Назад для выхода из скрытого приложения | Приложение скрывается, удаляется из недавних | То же, что выше |
| Пользователь открывает недавние, находясь в скрытом приложении | Приложение скрывается, не видно в недавних | То же + `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` |
| Скрытое приложение пытается отправить уведомление | **Невозможно** — приложение не может работать, пока скрыто | `setApplicationHiddenSetting` блокирует все запуски компонентов |
| Скрытое приложение обновлено системой | Остаётся скрытым после обновления | Система сохраняет скрытое состояние; `PackageMonitor` как дополнительная проверка |
| Lokker переустановлен/обновлён | Lokker виден в лаунчере, настройка `self_hidden` сброшена | `PackageMonitor` обнаруживает свой пакет → сбрасывает `self_hidden`; alias возвращается к значению по умолчанию из манифеста (включён) |
| Устройство перезагружается | Все скрытые приложения остаются скрытыми | `packages.xml` сохраняется; `BootReceiver` проверяет |
| Процесс Lokker убит во время временного раскрытия | **Приложение повторно скрывается при следующем запуске** | `pendingRehide` сохраняется в EncryptedPrefs; `recoverLeakedApps()` в `Application.onCreate()` |
| Пользователь добавляет приложение через графический интерфейс выбора | Приложение добавлено в Lokker (защищено паролем), ярлык создаётся автоматически | `addApplication()` → вставка в Room + `createPinnedShortcut()` + обновление LiveData |
| Пользователь удаляет приложение через графический интерфейс | Приложение удалено из Lokker, раскрыто, ярлык удалён | `removeApplication()` → `setApplicationHiddenSetting(false)` + удаление из Room + отключение ярлыка + очистка иконки |
| Пользователь скрывает управляемое приложение | Приложение исчезает из лаунчера/настроек | `hideApp()` → `setApplicationHiddenSetting(true)` + `setHidden(true)` |
| Пользователь раскрывает управляемое приложение | Приложение снова появляется, но остаётся в Lokker (по-прежнему защищено паролем) | `unhideApp()` → `setApplicationHiddenSetting(false)` + `setHidden(false)` |
| Пользователь нажимает на закреплённый ярлык | Аутентификация → раскрытие → запуск → повторное скрытие при переключении | Intent ярлыка → `AuthActivity` → стандартный процесс запуска |
| Переназначатель клавиш вызывает ярлык | То же, что нажатие на ярлык — аутентификация → запуск → повторное скрытие | Переназначатель использует intent `com.lokker.app.LAUNCH_HIDDEN` |
| Пользователь нажимает ⋮ → «Раскрыть» | Диалог подтверждения → приложение раскрыто, но остаётся в Lokker | `unhideApp()` → `setApplicationHiddenSetting(false)` + `setHidden(false)` |
| Пользователь нажимает ⋮ → «Удалить из Lokker» | Диалог подтверждения → приложение полностью удалено из управления Lokker | `removeApplication()` → раскрытие + удаление из Room + удаление ярлыка + очистка иконки |
| Пользователь нажимает переключатель «Раскрыть все приложения» в настройках | Все скрытые приложения раскрыты, но остаются в Lokker; снимок сохранён; переключатель меняется на «Скрыть все приложения» | `unhideAll()` — сохраняет имена пакетов, устанавливает `hidden=false` для каждого, очищает `pendingRehide` |
| Пользователь нажимает переключатель «Скрыть все приложения» в настройках | Все ранее раскрытые приложения повторно скрыты; переключатель возвращается к «Раскрыть все приложения» | `rehideAll()` — читает снимок, устанавливает `hidden=true` для каждого (пропускает удалённые/деинсталлированные), очищает снимок |

---

## 14. Полный манифест разрешений

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.lokker.app">

    <!-- Разрешения времени выполнения -->
    <uses-permission android:name="android.permission.USE_BIOMETRIC"/>
    <uses-permission android:name="android.permission.USE_FINGERPRINT"/>
    <uses-permission android:name="android.permission.VIBRATE"/>
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"/>

    <!-- Привилегированные разрешения (внесены в белый список в privapp XML) -->
    <uses-permission android:name="android.permission.MANAGE_USERS"/>
    <uses-permission android:name="android.permission.CHANGE_COMPONENT_ENABLED_STATE"/>
    <uses-permission android:name="android.permission.REMOVE_TASKS"/>
    <uses-permission android:name="android.permission.GET_TASKS"/>
    <uses-permission android:name="android.permission.REAL_GET_TASKS"/>
    <uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS"/>
    <uses-permission android:name="android.permission.MANAGE_ACTIVITY_STACKS"/>
</manifest>
```

> **Примечания по разрешениям:**
> - `MANAGE_USERS` — требуется для `setApplicationHiddenSetting()` (основной API скрытия)
> - `CHANGE_COMPONENT_ENABLED_STATE` — используется только для самоскрытия Lokker (activity-alias)
> - Объявление `NotificationListenerService` не требуется — скрытые приложения не могут отправлять уведомления

---

## 15. Фазы разработки

### Фаза 1 — Фундамент (~3 дня)
- Каркас модуля AOSP: `Android.bp` (с `platform_apis: true`), манифест, интеграция сборки
- XML `privapp-permissions` (включая `MANAGE_USERS`) + подключение `device.mk`
- `LokkerDatabase` (Room) + сущность `LokkerApp` (с булевым полем `hidden`) + DAO
- `LokkerPrefs` (EncryptedSharedPreferences) с сохранением `pendingRehide`
- Каркас `AppRepository` с подключением `setApplicationHiddenSetting`
- `LokkerApp.onCreate()` → `recoverLeakedApps()` (повторное скрытие утёкших приложений)
- Проверка работы `setApplicationHiddenSetting` как priv-app на устройстве
- Проверка исчезновения скрытого приложения из Настройки → Приложения

### Фаза 2 — Основное скрытие (~2 дня)
- Полная реализация `hideApp()` / `unhideApp()` / `unhideTemporarily()` / `rehideApp()`
- `BootReceiver` — проверка всех состояний скрытых приложений при загрузке (дополнительная страховка)
- `PackageMonitor` — проверка безопасности при `PACKAGE_REPLACED` / `PACKAGE_ADDED`
- Самоскрытие через activity-alias `setComponentEnabledSetting` (только иконка самого Lokker)
- `SecretCodeReceiver` (ввод кода набора — опционально, может не работать на всех прошивках)

### Фаза 3 — Графический интерфейс (~3 дня)
- Макет `MainActivity`: RecyclerView + FAB + меню панели инструментов
- `LokkerAppsAdapter` с иконкой приложения, именем, пакетом, переключателем скрытия/раскрытия
- `AppPickerDialog`: список всех установленных приложений с поиском
- `AppPickerAdapter` с множественным выбором чекбоксами
- Переключатель «Показать системные приложения» в окне выбора
- `LokkerViewModel` с LiveData из Room DAO + `MutableLiveData<String>` фильтр поискового запроса через `Transformations.switchMap`
- Состояние «пусто» при отсутствии скрытых приложений

### Фаза 4 — Слой аутентификации (~2 дня)
- `AuthManager`: хеширование пароля PBKDF2
- Интеграция `BiometricPrompt`
- `AuthActivity`: интерфейс ввода PIN + резервная биометрия
- `SetupActivity`: первоначальная настройка пароля
- Логика блокировки: 5 неудач → тайм-аут 30 секунд
- Защита `MainActivity` аутентификацией при запуске

### Фаза 5 — Accessibility-сервис + Повторное скрытие (~3 дня)
- Каркас `LokkerAccessibilityService` + объявление в манифесте
- Регистрация `TaskStackListener` (основное обнаружение приложения на переднем плане)
- `TYPE_WINDOW_STATE_CHANGED` (вторичное/резервное обнаружение переднего плана)
- Набор `pendingRehide`: сохраняется в EncryptedPrefs, отслеживает временно разблокированные приложения
- **Повторное скрытие при переключении приложения** — `setApplicationHiddenSetting(true)` + `removeTask()`
- `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` при запуске
- Автоматическое включение Accessibility через `WRITE_SECURE_SETTINGS`

### Фаза 6 — Горячие клавиши (~2 дня)
- `HotkeyManager`: буфер последовательности + логика тайм-аута
- `onKeyEvent` в AccessibilityService
- `HotkeySetupActivity`: запись и сохранение последовательностей
- Поддержка горячих клавиш для отдельных приложений

### Фаза 7 — Полировка и тестирование (~3 дня)
- Проверка: скрытые приложения невидимы в Настройки → Приложения, `pm list packages`, статистике батареи
- Граничные случаи: приложения, которые повторно активируются самостоятельно
- Восстановление после гибели процесса: убить Lokker во время временного раскрытия, проверить повторное скрытие при перезапуске
- Тестирование на устройстве с LineageOS 22
- Тёмная тема, edge-to-edge (обязательно для Android 15)
- Режим без иконки: проверка невидимости Lokker во всех лаунчерах
- Стресс-тест: скрытие/раскрытие 20+ приложений
- `ScreenReceiver`: при разблокировке повторная проверка целостности всех скрытых состояний

**Общая оценка: ~18 рабочих дней для одного разработчика**

---

## Отложено — Патч фреймворка: перехват ActivityNotFoundException

**Статус:** Отложено для будущего рассмотрения
**Обоснование:** Обеспечивает бесшовную интеграцию с переназначателями клавиш (например, KeyMapper) без необходимости настройки пользователем прокси-активности. Переназначатель может нацеливаться непосредственно на реальный компонент скрытого приложения — Lokker перехватывает неудавшийся запуск и автоматически обрабатывает аутентификацию + раскрытие.

### Проблема

Когда приложение скрыто через `setApplicationHiddenSetting`, его компоненты невидимы для `PackageManager.resolveActivity()`. Любой внешний инструмент (переназначатель клавиш, лаунчер ярлыков), пытающийся запустить скрытое приложение, получает `ActivityNotFoundException`. Как системное приложение, Lokker **не может** перехватить это — `IActivityController.activityStarting()` срабатывает только для успешно разрешённых активностей, а исключение выбрасывается на стороне клиента в процессе вызывающего.

### Решение: патч фреймворка LineageOS

Добавить ~15 строк в `ActivityStarter.java` в дереве исходного кода LineageOS. Когда разрешение intent'а не удаётся и целевой пакет установлен, но скрыт, отправить широковещательное сообщение о неудавшемся intent'е, чтобы Lokker мог его перехватить.

#### Сторона фреймворка (packages/services/core)

**Файл:** `frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java`

В `executeRequest()`, где возвращается `START_INTENT_NOT_RESOLVED` после `aInfo == null`:

```java
// После: if (aInfo == null) { ... }
// Проверяем, является ли цель скрытым (не удалённым) пакетом
if (aInfo == null && intent.getComponent() != null) {
    String targetPkg = intent.getComponent().getPackageName();
    try {
        PackageManager pm = mService.mContext.getPackageManager();
        // getApplicationInfo с флагом MATCH_HIDDEN — работает только для скрытых приложений
        ApplicationInfo ai = pm.getApplicationInfo(targetPkg,
            PackageManager.MATCH_UNINSTALLED_PACKAGES);
        if (ai != null) {
            Intent failedBroadcast = new Intent("android.intent.action.ACTIVITY_NOT_RESOLVED");
            failedBroadcast.putExtra("original_intent", intent);
            failedBroadcast.putExtra("calling_package", callingPackage);
            failedBroadcast.setPackage("com.lokker.app");  // адресно — получает только Lokker
            failedBroadcast.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            mService.mContext.sendBroadcastAsUser(failedBroadcast,
                UserHandle.of(userId),
                android.Manifest.permission.MANAGE_USERS);
        }
    } catch (PackageManager.NameNotFoundException ignored) {
        // Действительно удалено — широковещательное сообщение не нужно
    }
}
```

#### Сторона Lokker (ресивер)

**Файл:** `src/com/lokker/app/receiver/ActivityNotResolvedReceiver.java`

```java
public class ActivityNotResolvedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent original = intent.getParcelableExtra("original_intent", Intent.class);
        if (original == null || original.getComponent() == null) return;

        String targetPkg = original.getComponent().getPackageName();
        AppRepository repo = AppRepository.getInstance(context);

        if (repo.isManagedApp(targetPkg)) {
            // Запуск шлюза аутентификации → при успехе: раскрытие + запуск оригинального intent'а
            Intent auth = new Intent(context, AuthActivity.class);
            auth.putExtra("target_package", targetPkg);
            auth.putExtra("original_intent", original);
            auth.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            context.startActivity(auth);
        }
    }
}
```

**Запись в манифесте:**

```xml
<receiver android:name=".receiver.ActivityNotResolvedReceiver"
    android:permission="android.permission.MANAGE_USERS"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.ACTIVITY_NOT_RESOLVED"/>
    </intent-filter>
</receiver>
```

### Почему отложено

- Требует поддержки патча фреймворка при обновлениях LineageOS
- Добавляет связанность между Lokker и кастомной сборкой прошивки
- Подход с прокси-активностью (Вариант A) работает без изменений фреймворка
- Можно вернуться к рассмотрению, когда основное приложение будет стабильным и протестированным
