# Lokker — Спецификация системного приложения

**Пакет:** `com.lokker.app`
**Целевая платформа:** LineageOS 22 · Android 15 · API 35
**Язык:** Java
**Тип:** Привилегированный системный APK (`/system/system_ext/priv-app`)
**Версия реализации:** v1.2b (ветка `claude/lokker-system-app-yTUtg`)

---

## 0. Статус документа и отличия реализации от исходного дизайна

Изначально документ был проектной спецификацией. Теперь он приведён к фактическому состоянию кода v1.2b. Если текст и код расходятся, верен код. Ключевые изменения относительно первоначального дизайна:

| Тема | Исходный дизайн | Фактическая реализация |
|---|---|---|
| Сборка | `android_app` из исходников в дереве AOSP | Gradle-сборка APK + `android_app_import` готового APK в образ (Раздел 11) |
| Вызов скрытого API | Прямой вызов `pm.setApplicationHiddenSetting()` | Рефлексия: `IPackageManager.setApplicationHiddenSettingAsUser()` через `ServiceManager.getService("package")` |
| Коды номеронабирателя | `*#5655#` | `*#*#565537#*#*` — открыть Lokker; `*#*#5655377469#*#*` — раскрыть **все** скрытые пакеты на устройстве |
| Горячая клавиша по умолчанию | Громкость↑ Громкость↑ Громкость↓ | Нет значения по умолчанию — пользователь записывает свою |
| Типы нажатий | Только короткое нажатие | Короткое, долгое (500 мс), двойное и тройное (интервал 400 мс), удержание + следующая клавиша (Раздел 9) |
| Режимы раскрытия | Только временное (повторное скрытие при уходе с переднего плана) | Временное + **«раскрыть до закрытия»** (повторное скрытие после удаления задачи из недавних) (Раздел 4) |
| Закреплённые ярлыки | Создаются автоматически в `addApplication()` | **Не создаются.** Вместо них есть `CreateShortcutActivity` (`ACTION_CREATE_SHORTCUT`) для KeyMapper и других переназначателей клавиш |
| Динамические ярлыки | — | Были добавлены, затем удалены в v1.1: они показывали скрытые приложения в поиске лаунчера |
| Импорт/экспорт настроек | Секция «Данные» в настройках | Удалён полностью (UI — f258db7, остатки кода — 2026-10-16) |
| Состояние самоскрытия | Флаг `self_hidden` в настройках | Читается из состояния компонента `LokkerLauncher` (Раздел 5) |
| PBKDF2 | 310 000 итераций | 10 000 итераций. Хеши на 310 000 итераций проверяются как устаревшие и перехешируются при успешном входе |
| Биометрия | Сначала биометрия, `BIOMETRIC_STRONG \| DEVICE_CREDENTIAL` | Только `BIOMETRIC_STRONG`, по умолчанию выключена, показывается поверх экрана PIN |
| Включение Accessibility | Вручную | Автоматически в `LokkerApp.onCreate()` через `WRITE_SECURE_SETTINGS` |
| Добавление из выбора приложений | Только `addApplication()` | `addApplication()` + сразу `hideApp()` |
| Сообщения пользователю | Snackbar | Toast |
| Язык UI | Русский | Английский по умолчанию + перевод `values-ru` |

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
- Режим **«раскрыть до закрытия»**: приложение остаётся видимым, пока пользователь не закроет его задачу в недавних
- Интеграция с переназначателями клавиш (KeyMapper и др.) через `ACTION_CREATE_SHORTCUT`
- Секретные коды номеронабирателя: `*#*#565537#*#*` (открыть Lokker) и `*#*#5655377469#*#*` (аварийно раскрыть все приложения)

### Не входит в объём проекта

- Шифрование файлов / SafeBox
- Галерея / файловый менеджер
- Облачное резервное копирование / синхронизация
- Многопользовательский режим / Рабочий профиль
- Удалённое стирание данных
- Изоляция сетевого трафика

---

## 2. Архитектура

Одномодульное Java-приложение (Gradle, модуль `:app`). Паттерн MVVM-lite. Кроме AndroidX, Material и Security Crypto внешних зависимостей нет. Интерфейс в основном собирается программно в `buildUi()` каждой активности, а не из XML-разметки. Четыре типа компонентов времени выполнения.

```
┌─────────────────────────────────────────────────────────────┐
│                      Lokker Process                         │
├──────────────┬──────────────┬──────────────┬───────────────┤
│   UI Layer   │  ViewModel   │   Services   │  BroadcastRx  │
│              │              │              │               │
│ MainActivity │ (нет; LiveData│LokkerAccessSvc│ PackageMonitor│
│ AuthActivity │ из DAO)      │              │ BootReceiver  │
│ SettingsAct  │              │              │ SecretCodeRx  │
│ ChangePwAct  │              │              │ ScreenReceiver│
│ HotkeySetup  │              │              │               │
│ AppPicker    │              │              │               │
│ CreateShortcut│             │              │               │
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
| `LokkerApp` (Application) | Application | При старте: `hidden_api_policy=1`, автоматическое включение Accessibility-сервиса, `recoverLeakedApps()` |
| `LokkerAccessibilityService` | AccessibilityService | Обнаружение смены приложения на переднем плане через `TYPE_WINDOW_STATE_CHANGED` (основной путь); **повторное скрытие при потере фокуса временно раскрытым приложением**; удаление из списка недавних; проверка приложений в режиме «до закрытия» (+ `TaskStackListener.onTaskRemoved`, опрос раз в 3 с); обнаружение и запись горячих клавиш; динамическая регистрация `ScreenReceiver` |
| `PackageMonitor` | BroadcastReceiver | `PACKAGE_REPLACED`/`ADDED` — повторное применение состояния скрытия после обновлений (дополнительная страховка); собственный пакет пропускает |
| `BootReceiver` | BroadcastReceiver | `BOOT_COMPLETED` — повторное применение скрытия ко всем приложениям с `hidden=1` |
| `ScreenReceiver` | BroadcastReceiver | `SCREEN_ON` — повторное применение скрытия (регистрируется динамически в сервисе, не в манифесте) |
| `SecretCodeReceiver` | BroadcastReceiver | `*#*#565537#*#*` → `AuthActivity`; `*#*#5655377469#*#*` → `unhideAllSystemWide()` без аутентификации |
| `CreateShortcutActivity` | Activity | `ACTION_CREATE_SHORTCUT`: список «Открыть Lokker» + управляемые приложения → возвращает intent ярлыка с кэшированной иконкой |
| `AppRepository` | Репозиторий (синглтон) | Единый интерфейс к Room DB + EncryptedSharedPreferences + `setApplicationHiddenSettingAsUser` (рефлексия) |

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

`setApplicationHiddenSetting()` требует `MANAGE_USERS` (signature|privileged). Lokker подписывается платформенным сертификатом (`certificate: "platform"` в `deployment/Android.bp`), поэтому разрешение выдаётся автоматически. Также нужна запись в белом списке `com.lokker.app.privapp-permissions.xml`.

### Реализация вызова

Gradle-сборка компилируется против публичного SDK, поэтому скрытый API вызывается через рефлексию на binder-интерфейсе `IPackageManager`:

```java
// AppRepository.java
public static void setApplicationHiddenSetting(String packageName, boolean hidden) {
    IBinder binder = ServiceManager.getService("package");          // через рефлексию
    Object ipm = IPackageManager.Stub.asInterface(binder);          // через рефлексию
    int userId = Process.myUserHandle().hashCode();
    ipm.setApplicationHiddenSettingAsUser(packageName, hidden, userId); // через рефлексию
}
```

`LokkerApp.onCreate()` записывает `Settings.Global.hidden_api_policy = 1`, чтобы рефлексия на скрытых API работала и в release-сборке.

### addApplication(packageName)

Добавляет приложение в управляемый список Lokker: кэширует название и иконку (PNG в `files/icons/`) и сохраняет запись в Room. **Не** скрывает приложение и **не** создаёт ярлыков. Экран выбора приложений сразу после этого вызывает `hideApp()`.

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
}
```

### removeApplication(packageName)

Полностью удаляет приложение из Lokker: снимает скрытие, удаляет запись из Room, убирает пакет из `pendingRehide` и `pendingRehideOnClose`, очищает кэшированную иконку. Приложение возвращается в нормальное состояние.

```java
// AppRepository.java

public void removeApplication(String packageName) {
    setApplicationHiddenSetting(packageName, false);

    appDao.delete(packageName);
    pendingRehide.remove(packageName);
    persistPendingRehide();
    pendingRehideOnClose.remove(packageName);
    persistPendingRehideOnClose();

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

> **Примечание:** здесь и ниже `pm.setApplicationHiddenSetting(...)` в примерах означает статический `AppRepository.setApplicationHiddenSetting(...)` (см. «Реализация вызова» выше).

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

### unhideUntilClosed(packageName) — режим «раскрыть до закрытия» (v1.1)

Раскрывает приложение так, что оно **не** скрывается при уходе с переднего плана. Повторное скрытие происходит только после того, как задача приложения исчезнет из списка недавних (пользователь смахнул её). Подробнее см. Раздел 4.

```java
public boolean unhideUntilClosed(String packageName) {
    LokkerApp record = appDao.get(packageName);
    if (record == null || !record.hidden) return false;

    setApplicationHiddenSetting(packageName, false);
    pendingRehideOnClose.add(packageName);
    // 5 с льготного периода, чтобы приложение успело создать задачу
    rehideOnCloseGrace.put(packageName, SystemClock.elapsedRealtime() + 5000);
    persistPendingRehideOnClose();
    return true;
}
```

Как включается этот режим:
- Контекстное меню ⋮ → «Раскрыть до закрытия» (пункт показан только для скрытых приложений).
- Горячая клавиша приложения, если в её настройке включён переключатель «Раскрыть до закрытия» (`LokkerApp.hotkeyUntilClosed`). Работает только при запуске с горячей клавиши (`from_hotkey=true`). Запуск через ярлык или переназначатель клавиш всегда использует временное раскрытие.

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

/** Вызывается из LokkerApp.onCreate() (в фоновом потоке) — повторно скрываем утёкшие приложения */
public void recoverLeakedApps() {
    loadPendingRehide();
    for (String pkg : new HashSet<>(pendingRehide)) {
        setApplicationHiddenSetting(pkg, true);
        pendingRehide.remove(pkg);
    }
    persistPendingRehide();

    // То же для набора «раскрыть до закрытия»
    loadPendingRehideOnClose();
    for (String pkg : new HashSet<>(pendingRehideOnClose)) {
        setApplicationHiddenSetting(pkg, true);
        pendingRehideOnClose.remove(pkg);
    }
    persistPendingRehideOnClose();
}
```

Набор `pendingRehideOnClose` хранится в ключе `pending_rehide_on_close` так же, как `pending_rehide`.

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

### Обнаружение смены переднего плана

#### Основной: AccessibilityService (`TYPE_WINDOW_STATE_CHANGED`)

Фактически работающий механизм. События от `com.android.systemui` и от любых методов ввода (IME, проверка через `InputMethodManager.getInputMethodList()`) игнорируются: иначе шторка или появление клавиатуры ошибочно считались бы уходом из приложения (исправлено в коммите cfb3c72 «apps hiding on keyboard input»).

```java
// LokkerAccessibilityService.java

@Override
public void onAccessibilityEvent(AccessibilityEvent event) {
    if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
    String newPkg = String.valueOf(event.getPackageName());

    if (newPkg.equals("com.android.systemui")) return;
    if (isInputMethod(newPkg)) return;

    if (!newPkg.equals(currentForegroundPkg)) {
        String previousPkg = currentForegroundPkg;
        currentForegroundPkg = newPkg;

        // Ушли с временно раскрытого приложения → скрыть и убрать из недавних
        if (previousPkg != null && repo.isPendingRehide(previousPkg)) {
            executor.execute(() -> {
                repo.rehideApp(previousPkg);
                removeFromRecents(previousPkg);
            });
        }

        // Проверка приложений в режиме «до закрытия»
        if (repo.hasPendingRehideOnClose()) {
            executor.execute(this::checkRehideOnClose);
        }
    }
}
```

#### Вспомогательный: TaskStackListener (скрытый API, best-effort)

Регистрируется в `onServiceConnected()` через рефлексию (`ServiceManager.getService("activity_task")` → `IActivityTaskManager.registerTaskStackListener`, слушатель создаётся через `java.lang.reflect.Proxy`). Любая ошибка регистрации только пишется в лог. Используется **только** `onTaskRemoved` → `checkRehideOnClose()`. `onTaskMovedToFront` лишь логируется и не участвует в повторном скрытии.

#### Режим «раскрыть до закрытия»: checkRehideOnClose()

Выполняется только в потоке `executor`. Для каждого пакета из `pendingRehideOnClose`, у которого прошёл 5-секундный льготный период, проверяется, есть ли его задача в `getRecentTasks(100, RECENT_WITH_EXCLUDED)`. Если задачи нет, пакет скрывается (`rehideClosedApp()`). Пока набор не пуст, работает опрос раз в 3 секунды. Он нужен, потому что смахивание задачи из недавних не всегда даёт accessibility-событие. Когда набор пуст, опрос останавливается.

Триггеры проверки: смена переднего плана, `TaskStackListener.onTaskRemoved`, периодический опрос.

```java
// AppRepository.java
public void rehideApp(String packageName) {
    setApplicationHiddenSetting(packageName, true);
    pendingRehide.remove(packageName);
    persistPendingRehide();
}
```

### Запуск и удаление из списка недавних

```java
// AppRepository.java — вызывающий код ПРЕДВАРИТЕЛЬНО вызывает
// unhideTemporarily() или unhideUntilClosed() (скрытые приложения
// возвращают null из getLaunchIntentForPackage)
public void launchHiddenApp(String packageName, boolean excludeFromRecents) {
    Intent intent = pm.getLaunchIntentForPackage(packageName);
    if (intent == null) {
        // Активность лаунчера могла остаться отключённой — вернуть DEFAULT
        repairDisabledLauncherActivity(packageName);
        intent = pm.getLaunchIntentForPackage(packageName);
    }
    if (intent == null) return;

    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    if (excludeFromRecents) {      // false для режима «до закрытия»
        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    }
    ctx.startActivity(intent);
}
```

`AuthActivity` делает паузу между раскрытием и запуском, чтобы PackageManager успел обновить кэш: 150 мс для временного режима и 500 мс для режима «до закрытия».

`LokkerAccessibilityService.removeFromRecents()` убирает задачи пакета в два этапа: сначала `AppTask.finishAndRemoveTask()` для собственных задач, затем рефлексивный `ActivityManager.removeTask(id)` по `getRecentTasks(50, RECENT_WITH_EXCLUDED)`.

### Диаграмма потока повторного скрытия

```
Пользователь открывает скрытое приложение через Lokker
  → Аутентификация (биометрия/PIN)
  → unhideTemporarily() — setApplicationHiddenSetting(pkg, false)
  → pendingRehide сохранён в EncryptedPrefs
  → startActivity с флагами EXCLUDE_FROM_RECENTS
  → пользователь нормально использует приложение
  → пользователь нажимает Home / Назад / переключает приложение
  → AccessibilityService (TYPE_WINDOW_STATE_CHANGED) обнаруживает смену переднего плана
  → предыдущий пакет был в pendingRehide
  → setApplicationHiddenSetting(pkg, true) — полное повторное скрытие на системном уровне
  → removeFromRecents() — очищает из списка задач
  → pendingRehide очищен и сохранён
  → готово — приложение снова полностью невидимо
```

```
Режим «раскрыть до закрытия»
  → ⋮ «Раскрыть до закрытия» ИЛИ горячая клавиша приложения с hotkeyUntilClosed=true
  → (для горячей клавиши — аутентификация)
  → unhideUntilClosed() — pendingRehideOnClose + льготный период 5 с
  → startActivity БЕЗ EXCLUDE_FROM_RECENTS (задача видна в недавних)
  → пользователь свободно переключается между приложениями — скрытия нет
  → пользователь смахивает задачу из недавних
  → onTaskRemoved / смена переднего плана / опрос 3 с → checkRehideOnClose()
  → задачи нет в getRecentTasks → rehideClosedApp() — приложение снова скрыто
```

### Восстановление после смерти процесса

Если Lokker убит, пока приложение временно раскрыто, `LokkerApp.onCreate()` вызывает `recoverLeakedApps()`, который сканирует сохранённый набор `pendingRehide` и немедленно повторно скрывает все утёкшие приложения (см. Раздел 3).

---

## 5. Самоскрытие и секретный вход

Lokker может скрыть себя из лаунчера **переключателем** в Настройках. При самоскрытии иконка полностью исчезает. Открыть Lokker можно по настроенной горячей клавише (значения по умолчанию нет: без записанной горячей клавиши самоскрытие запрещено), секретным кодом номеронабирателя `*#*#565537#*#*` или ярлыком «Открыть Lokker» из переназначателя клавиш (`CreateShortcutActivity`).

**Восстановление** (если горячая клавиша забыта): `adb shell pm enable com.lokker.app/.LokkerLauncher`.

### Псевдонимы в манифесте

Две записи Activity: реальная `MainActivity` (никогда не отключается, доступна через явный intent) и псевдоним `LokkerLauncher` (отключается при самоскрытии).

```xml
<!-- Реальная активность — всегда существует, никогда не отключается -->
<activity
    android:name=".ui.MainActivity"
    android:exported="true"
    android:excludeFromRecents="true">
    <intent-filter>
        <action android:name="com.lokker.app.OPEN"/>
        <category android:name="android.intent.category.DEFAULT"/>
    </intent-filter>
</activity>

<!-- Псевдоним для лаунчера — ЭТОТ отключается при самоскрытии -->
<activity-alias
    android:name=".LokkerLauncher"
    android:targetActivity=".ui.MainActivity"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
    </intent-filter>
</activity-alias>
```

### setSelfHidden()

Самоскрытие управляется переключателем в настройках. Перед включением система **должна** убедиться, что горячая клавиша Lokker настроена — иначе пользователь заблокирует сам себя.

```java
// AppRepository.java
public void setSelfHidden(boolean hidden) {
    if (hidden) {
        // Защита: отказ от самоскрытия, если горячая клавиша Lokker не настроена
        HotkeyMap hk = hotkeyMapDao.get();
        if (hk == null || hk.lokkerHotkey == null || hk.lokkerHotkey.isEmpty()) {
            throw new IllegalStateException("Cannot self-hide without a configured hotkey");
        }
    }

    ComponentName alias = new ComponentName(ctx, "com.lokker.app.LokkerLauncher");
    int state = hidden
        ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

    pm.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP);
}

public boolean isSelfHidden() {
    ComponentName alias = new ComponentName(ctx, "com.lokker.app.LokkerLauncher");
    return pm.getComponentEnabledSetting(alias)
        == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
}
```

### Источник истины для состояния самоскрытия

Отдельного флага в настройках **нет**. `AppRepository.isSelfHidden()` каждый раз читает фактическое состояние alias через `pm.getComponentEnabledSetting(LokkerLauncher) == COMPONENT_ENABLED_STATE_DISABLED`. Поэтому переключатель в настройках всегда совпадает с тем, что видно в лаунчере, в том числе после обновления или переустановки Lokker и после `adb shell pm enable`. `PackageMonitor` пакет самого Lokker просто пропускает.

> **История:** до этого исправления (2026-10-16) самоскрытие было реализовано дважды. `SettingsActivity` писал флаг `stealth_mode`, а неиспользуемый `AppRepository.setSelfHidden()` писал `self_hidden`, и `PackageMonitor` сбрасывал именно `self_hidden`. В итоге после переустановки переключатель мог показывать «включено» при видимой иконке. На устройствах, обновлённых со старых версий, ключи `stealth_mode`/`self_hidden` могут остаться в настройках, но больше не читаются.

### Секретные коды номеронабирателя

| Код | Мнемоника | Действие | Аутентификация |
|---|---|---|---|
| `*#*#565537#*#*` | LOKKER | Открыть `AuthActivity` → `MainActivity` | Да |
| `*#*#5655377469#*#*` | LOKKERSHOW | `unhideAllSystemWide()`: `unhideAll()` по списку Lokker, затем раскрытие **всех** пакетов устройства через `IPackageManager.getInstalledApplications(MATCH_UNINSTALLED_PACKAGES)`. Аварийное восстановление, работает даже после очистки данных Lokker | **Нет** |

```xml
<receiver android:name=".receiver.SecretCodeReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.provider.Telephony.SECRET_CODE"/>
        <action android:name="android.telecom.action.SECRET_CODE"/>
        <data android:scheme="android_secret_code" android:host="565537"/>
    </intent-filter>
    <intent-filter>
        <action android:name="android.provider.Telephony.SECRET_CODE"/>
        <action android:name="android.telecom.action.SECRET_CODE"/>
        <data android:scheme="android_secret_code" android:host="5655377469"/>
    </intent-filter>
</receiver>
```

---

## 6. Уровень аутентификации

Двухуровневая аутентификация: **хеш PIN** (ровно 6 цифр) + опциональный **BiometricPrompt** (отпечаток пальца/лицо).

### Хранение пароля

PIN хранится как хеш PBKDF2-HMAC-SHA256 в EncryptedSharedPreferences, формат `Base64(salt):Base64(hash)`, соль 16 байт, ключ 256 бит. PBKDF2 выбран вместо Argon2, чтобы не тянуть нативную библиотеку.

**Итерации:** сейчас `ITERATIONS = 10_000`. Изначально было 310 000. `verifyPassword()` сначала проверяет хеш на 10 000 итераций, затем как устаревший на 310 000. При совпадении с устаревшим хешем PIN перехешируется с текущим числом итераций.

Счётчик ошибок и время окончания блокировки хранятся **строками** (`putString`), потому что `LokkerPrefs` предоставляет только методы для boolean и String.

Ниже исходный код из дизайна. Реальный `AuthManager` отличается только числом итераций и проверкой устаревшего хеша.

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

Реализован прямо в `AuthActivity.showBiometricPrompt()`:
- Показывается, только если переключатель «Биометрия» включён (`biometric_enrolled`, **по умолчанию выключен**) и `BiometricManager.canAuthenticate(BIOMETRIC_STRONG) == BIOMETRIC_SUCCESS`.
- Принимается только `BIOMETRIC_STRONG`, без `DEVICE_CREDENTIAL`. Кнопка отмены «Cancel» закрывает диалог и оставляет экран PIN.
- Ошибки и неудачные попытки биометрии не увеличивают счётчик блокировки.
- Кнопку на клавиатуре PIN (левый нижний угол) можно нажать, чтобы вызвать биометрию повторно.

### Поток AuthActivity

1. **Цель запуска** определяется из extra `target_package` либо из data URI `lokker://launch/{package}`. URI надёжнее переживает сериализацию ярлыков и PendingIntent. Если цели нет, открывается сам Lokker.
2. **Если PIN не установлен** (`pw_hash` отсутствует) → сразу `proceedAfterAuth()`.
3. Строится экран PIN. Если активна блокировка, сразу запускается таймер обратного отсчёта.
4. Если биометрия включена → поверх экрана PIN показывается BiometricPrompt.
5. **Ввод PIN** тремя способами: экранная клавиатура 4×3, аппаратная клавиатура (`KEYCODE_0..9`, `NUMPAD_0..9`, `DEL`, `ENTER`), IME через скрытый `EditText`. Введённые цифры показываются как `*`, пустые позиции как `_`. После 6-й цифры PIN проверяется автоматически.
6. **Неверный PIN** → тряска индикаторов + Toast «Wrong PIN (attempt N/5)».
7. **5 неудач** → блокировка на 30 с (`lockout_until`), клавиатура затемнена, таймер «Try again in N sec.».
8. **Успех** (`proceedAfterAuth()`):
   - есть цель, `from_hotkey=true` и у приложения `hotkeyUntilClosed=true` → `unhideUntilClosed()` → пауза 500 мс → `launchHiddenApp(pkg, false)`;
   - есть цель, иначе → `unhideTemporarily()` → пауза 150 мс → `launchHiddenApp(pkg)`;
   - нет цели и активность вызвана через `startActivityForResult` (из `MainActivity`) → `RESULT_OK`;
   - нет цели → `MainActivity` с extra `authenticated=true`.

### Аутентификация MainActivity

`MainActivity` при каждом `onResume()` проверяет флаг `authenticated`. Если PIN установлен, а флага нет, запускается `AuthActivity` через `startActivityForResult`. Флаг сбрасывается в `onStop()`, кроме переходов внутри приложения (`navigatingInternally`: настройки, выбор приложений). Поэтому любой выход из Lokker снова требует PIN.

> **Длина PIN:** ровно 6 цифр (`PIN_LENGTH = 6` в `AuthActivity`; `MIN_PASSWORD_LENGTH = 6` и цифровая клавиатура в `ChangePasswordActivity`).

> **Безопасность:** у всех активностей `excludeFromRecents="true"`. `showWhenLocked` не задаётся.

> **Первый запуск:** Пароль устанавливается в Настройках → Безопасность → «Установить пароль». До установки пароля Lokker открывается без аутентификации.

---

## 7. Графический интерфейс — Экран управления приложениями

Основной интерфейс — `MainActivity`. Все экраны строятся программно и следуют системной теме (светлой или тёмной). Строки интерфейса на английском (`values/strings.xml`), есть русский перевод (`values-ru/strings.xml`).

### 7.1 Список управляемых приложений (главный экран)

- **Панель инструментов**: заголовок «Lokker» и кнопка ⚙ (настройки).
- **Строка поиска** фильтрует список по имени и пакету в реальном времени.
- RecyclerView со **всеми управляемыми** приложениями, скрытыми и раскрытыми, отсортированными по `appLabel` (`getAllLive()`).
- Строка: **иконка** (из кэша) | **имя** | **пакет** (приглушённо) | **кнопка ⋮**.
- **Нажатие на строку** → Toast «Launching …» → `unhideTemporarily()` → `launchHiddenApp()`. Отдельной аутентификации нет: пользователь уже прошёл её при входе в `MainActivity`.
- **Кнопка ⋮** → PopupMenu:
  - **Assign hotkey** — нижняя панель записи горячей клавиши для приложения (см. 7.6);
  - **Launch** — то же, что нажатие на строку;
  - **Unhide until closed** — `unhideUntilClosed()` → `launchHiddenApp(pkg, false)`; пункт виден только у скрытых приложений;
  - **Remove from Lokker** — подтверждение → `removeApplication()`.
- **FAB +** → `AppPickerDialog`.
- Пустое состояние: «No hidden apps. Tap + to hide an app.»

### 7.2 Выбор приложений (`AppPickerDialog`, отдельная Activity)

- Строка поиска по имени и пакету.
- По умолчанию в списке только пользовательские приложения. Переключатель «Show system apps» добавляет системные (`FLAG_SYSTEM`).
- Строка: иконка | имя | пакет | чекбокс, можно выбрать несколько.
- Уже управляемые приложения показаны с меткой «Hidden» и недоступны для выбора.
- Кнопка внизу «Hide N app(s)» (без выбора — «Select apps»): для каждого выбранного приложения **`addApplication()` + `hideApp()`**, затем экран закрывается через 600 мс.

### 7.3 Экран настроек (`SettingsActivity`)

#### Секция «Security»

- **Hide Lokker from launcher** — переключатель самоскрытия. Если горячая клавиша Lokker не записана, показывается Toast «Set up a hotkey first» и переключатель возвращается назад. Иначе — подтверждение «Lokker will be hidden from the launcher. You can only open it with a hotkey. Continue?».
- **Set password / Change password** → `ChangePasswordActivity` (см. 7.5). Подпись: «Not set» / «Change PIN or password».
- **Biometrics** — переключатель `biometric_enrolled`. **По умолчанию выключен.**

#### Секция «Hotkeys»

- **Hotkey setup** → `HotkeySetupActivity`. Подпись: «N configured» (сколько всего назначено горячих клавиш) или «Not set».

#### Секция «Management»

Два отдельных пункта, каждый с подтверждением:
- **Unhide all apps** — «This will unhide all N hidden apps…» → `unhideAll()`: сохраняет снимок, раскрывает приложения и очищает оба набора pending.
- **Hide all apps** — «Re-hide all previously unhidden apps?» → `rehideAll()` по снимку.

#### Секция «Data» — удалена

Импорт и экспорт настроек (JSON через `ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`) удалены из интерфейса в коммите f258db7. Оставшиеся методы `exportSettings()` / `importSettings()` и строки `export_*`/`import_*` удалены 2026-10-16.

#### Секция «About»

- **Lokker** + `versionName` из PackageManager; платформа «LineageOS 22 · Android 15».

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

[⋮] = контекстное меню (→ Assign hotkey / Launch / Unhide until closed / Remove from Lokker)
[+ FAB] = открыть выбор приложений
Нажатие на строку = временное раскрытие → запуск → повторное скрытие при уходе
(PIN уже введён при входе в MainActivity)
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

### 7.5 Экран установки / смены пароля

Отдельный экран с кнопкой «← Назад» → возврат в настройки.

**Режим создания** (пароль не установлен): Заголовок: «Установить пароль». Два поля ввода:
- **Новый пароль** — 6-значный PIN (цифровая клавиатура, ввод маскируется)
- **Подтвердите пароль** — повторный ввод для подтверждения

**Режим смены** (пароль установлен): Заголовок: «Сменить пароль». Три поля ввода:
- **Текущий пароль** — ввод текущего PIN/пароля для подтверждения
- **Новый пароль** — новый 6-значный PIN
- **Подтвердите новый пароль** — повторный ввод для подтверждения

**Валидация:**
- Текущий пароль неверен → Toast «Wrong current password»
- Новый PIN короче 6 цифр → Toast «PIN must be at least 6 digits»
- Пароли не совпадают → Toast «Passwords don't match»

Кнопка **«Save»** внизу экрана. При успехе: `AuthManager.setPassword()`, Toast «Password set» / «Password changed successfully», возврат в настройки.

```
Режим создания:                     Режим смены:
┌──────────────────────────────┐    ┌──────────────────────────────┐
│ [←]  Установить пароль       │    │ [←]  Сменить пароль          │
├──────────────────────────────┤    ├──────────────────────────────┤
│                              │    │                              │
│                              │    │  Текущий пароль              │
│                              │    │  ┌────────────────────────┐  │
│                              │    │  │ ••••••                 │  │
│                              │    │  └────────────────────────┘  │
│  Новый пароль                │    │                              │
│  ┌────────────────────────┐  │    │  Новый пароль                │
│  │ Введите PIN или пароль │  │    │  ┌────────────────────────┐  │
│  └────────────────────────┘  │    │  │ Введите новый пароль   │  │
│                              │    │  └────────────────────────┘  │
│  Подтвердите пароль          │    │                              │
│  ┌────────────────────────┐  │    │  Подтвердите новый пароль    │
│  │ Повторите пароль       │  │    │  ┌────────────────────────┐  │
│  └────────────────────────┘  │    │  │ Повторите новый пароль │  │
│                              │    │  └────────────────────────┘  │
│  [       Сохранить         ] │    │                              │
└──────────────────────────────┘    │  [       Сохранить         ] │
                                    └──────────────────────────────┘
```

### 7.6 Экран настройки горячих клавиш (`HotkeySetupActivity`)

Запись идёт **через Accessibility-сервис** (`LokkerAccessibilityService.startRecording(listener)`): только он получает аппаратные клавиши, в том числе громкость при выключенном экране приложения. Во время записи клавиши поглощаются и не передаются системе. Исключение — `KEYCODE_BACK`.

**Правила записи:**
- Запись начинается нажатием кнопки «Tap to record a new sequence». Если в течение 5 с нет ни одного нажатия, запись завершается пустой.
- После каждой клавиши запускается таймер 1.5 с. Когда он истекает, последовательность считается завершённой.
- Типы нажатий (см. Раздел 9): короткое, `LONG` (≥500 мс), `2×`/`3×` (повтор той же клавиши в пределах 400 мс), `HOLD` (клавиша ещё нажата, когда нажата следующая).
- Бейджи показывают имя `KeyEvent.keyCodeToString()` без `KEYCODE_` с префиксом типа, например `[VOLUME_UP] [LONG VOLUME_DOWN] [2× CAMERA]`.
- **Проверка конфликтов:** если такая же последовательность уже назначена Lokker или другому приложению, сохранение отклоняется с Toast «This combination is already assigned: …».

#### Секция «Open Lokker»

Бейджи текущей последовательности (или «Not set») и кнопка записи. По завершении — Toast «Hotkey saved».

#### Секция «App hotkeys»

Список управляемых приложений: имя и текущая последовательность (или «Not set»). Нажатие открывает нижнюю панель записи.

#### Нижняя панель записи горячей клавиши приложения

Открывается из ⋮ → «Assign hotkey» на главном экране или из списка выше. Содержит заголовок (имя приложения), бейджи и индикатор записи, переключатель **«Unhide until closed»** (`hotkeyUntilClosed`) и кнопки «Reset» / «Save».

### 7.7 Ярлыки для переназначателей клавиш (`CreateShortcutActivity`)

> **Изменение относительно дизайна:** закреплённые ярлыки на домашнем экране (`requestPinShortcut`) **не создаются**. Динамические ярлыки (долгое нажатие на иконку Lokker) были реализованы и удалены в v1.1: их было видно в поиске лаунчера, а значит, скрытые приложения выдавали себя.

Lokker обрабатывает стандартный `Intent.ACTION_CREATE_SHORTCUT`. KeyMapper и похожие приложения показывают Lokker в своём списке «ярлыков приложений»:

1. Переназначатель отправляет `ACTION_CREATE_SHORTCUT` → открывается список «Lokker: choose app».
2. Первым пунктом всегда идёт **«Open Lokker»**, за ним все управляемые приложения.
3. Выбор → `RESULT_OK` с `EXTRA_SHORTCUT_INTENT`, `EXTRA_SHORTCUT_NAME`, `EXTRA_SHORTCUT_ICON` (кэшированная иконка приложения или иконка Lokker).
4. Intent ярлыка:
   - «Open Lokker»: `ACTION_MAIN` → `com.lokker.app.ui.AuthActivity`;
   - приложение: action `com.lokker.app.LAUNCH_HIDDEN`, data `lokker://launch/{package}`, extra `target_package`, флаги `NEW_TASK | EXCLUDE_FROM_RECENTS`.
5. При срабатывании ярлыка: `AuthActivity` → аутентификация → **временное** раскрытие → запуск → повторное скрытие при уходе. Флаг `from_hotkey` не передаётся, поэтому режим «до закрытия» здесь не применяется.

```xml
<activity android:name=".ui.AuthActivity"
    android:excludeFromRecents="true"
    android:exported="true">
    <intent-filter>
        <action android:name="com.lokker.app.LAUNCH_HIDDEN"/>
        <category android:name="android.intent.category.DEFAULT"/>
        <data android:scheme="lokker" android:host="launch"/>
    </intent-filter>
</activity>

<activity android:name=".ui.CreateShortcutActivity"
    android:exported="true"
    android:excludeFromRecents="true">
    <intent-filter>
        <action android:name="android.intent.action.CREATE_SHORTCUT"/>
        <category android:name="android.intent.category.DEFAULT"/>
    </intent-filter>
</activity>
```

#### Кэширование иконок приложений

Скрытые приложения невидимы для `PackageManager`, поэтому иконка сохраняется заранее, в `addApplication()`, в файл `files/icons/{package}.png`. Её используют главный экран, `CreateShortcutActivity` и `loadCachedIcon()`. В `removeApplication()` файл удаляется.

### unhideAll() / rehideAll()

Используются пунктами «Unhide all apps» / «Hide all apps» в Настройках. Меняют только флаг `hidden`, приложения остаются в управляемом списке Lokker. `unhideAll()` очищает также `pendingRehideOnClose`. Вариант для всей системы, `unhideAllSystemWide()`, описан в Разделе 5.

```java
public void unhideAll() {
    List<LokkerApp> hiddenApps = db.lokkerAppDao().getAllHidden();
    if (hiddenApps.isEmpty()) return;

    // Сохраняем снимок имён пакетов для последующего повторного скрытия
    saveUnhideAllSnapshot(hiddenApps);

    for (LokkerApp app : hiddenApps) {
        pm.setApplicationHiddenSetting(app.packageName, false);
        db.lokkerAppDao().setHidden(app.packageName, false);
        // Кэшированные иконки остаются на месте
    }

    pendingRehide.clear();
    persistPendingRehide();
    pendingRehideOnClose.clear();
    persistPendingRehideOnClose();
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

---

## 8. Подавление уведомлений

**Не требуется.** При использовании `setApplicationHiddenSetting` скрытые приложения не могут запускать какие-либо компоненты — ни сервисы, ни ресиверы, ни будильники. Они **не могут отправлять уведомления**. Система блокирует все запуски компонентов для скрытых пакетов на уровне `PackageManagerService`.

> **Примечание:** Во время кратковременного окна временного раскрытия (пока пользователь активно использует скрытое приложение) приложение МОЖЕТ отправлять уведомления. Это допустимо, поскольку пользователь активно использует приложение. Когда приложение повторно скрывается через `setApplicationHiddenSetting(pkg, true)`, приложение принудительно останавливается, и все ожидающие уведомления очищаются системой.

`LokkerNotificationListener` удалён из архитектуры. Разрешение `NotificationListenerService` не требуется.

---

## 9. Система горячих клавиш

Горячие клавиши обрабатываются в `LokkerAccessibilityService.onKeyEvent()` (флаг `FLAG_REQUEST_FILTER_KEY_EVENTS`, в конфигурации сервиса `canRequestFilterKeyEvents="true"`). Буфер последовательности и сопоставление реализованы в `HotkeyManager`.

> **Конфигурация сервиса** (`res/xml/accessibility_service_config.xml`): события только `typeWindowStateChanged`, флаг `flagRequestFilterKeyEvents`, `canRequestFilterKeyEvents="true"`. Флага `canRetrieveWindowContent` **нет** и он не нужен: сервис читает из события только тип и `getPackageName()`. Доступ к содержимому окон (`getSource()`, `getRootInActiveWindow()`) не используется, поэтому сервис не видит текст других приложений. Флаг удалён 2026-10-16.

### Кодирование типов нажатий

Последовательность хранится как `List<Integer>`. Тип нажатия кодируется в самом числе:

| Тип | Кодирование | Как распознаётся |
|---|---|---|
| Короткое | `keyCode` | Нажатие отпущено раньше 500 мс |
| Долгое (`LONG`) | `-keyCode` | Клавиша удерживается ≥ `LONG_PRESS_MS = 500` |
| Двойное (`2×`) | `keyCode + 10000` | Повторное нажатие той же клавиши < `DOUBLE_PRESS_MS = 400` после отпускания |
| Тройное (`3×`) | `keyCode + 20000` | Третье нажатие в том же окне |
| Удержание (`HOLD`) | `keyCode + 30000` | Клавиша ещё нажата, когда нажимается следующая. Пример: «удерживая Vol↓, нажать Vol↑» = `[VOL_DOWN+30000, VOL_UP]` |

`HotkeyManager.baseKeyCode()` снимает кодирование. По `isLongPress/isDoublePress/isTriplePress/isHold` определяется тип.

### Алгоритм в обычном режиме

1. `onKeyEvent()` выполняется в главном потоке и **никогда не поглощает** клавиши: всегда возвращается `super.onKeyEvent()`, и громкость с вводом работают как обычно.
2. Быстрый путь: если `keyCode` не встречается ни в одной настроенной горячей клавише (`HotkeyConfig.isHotkeyKey`), событие сразу пропускается дальше.
3. Остальная обработка уходит в однопоточный `executor`:
   - автоповторы (`repeatCount > 0`) отбрасываются, иначе удержание клавиши засчитывалось бы как много нажатий;
   - быстрое повторное нажатие повышает последний элемент буфера до 2× или 3×;
   - новая клавиша повышает предыдущую, ещё нажатую клавишу до `HOLD`, добавляется в буфер (тайм-аут бездействия 1500 мс очищает буфер) и запускает таймер 500 мс, который повышает её до `LONG`;
   - после каждого изменения: если буфер — префикс какой-либо горячей клавиши, срабатывает вибрация 100 мс (`USAGE_ALARM`); затем `checkHotkeys()`.
4. `checkHotkeys()`: сначала проверяется горячая клавиша Lokker (`endsWith`), затем горячие клавиши приложений. При совпадении буфер очищается и вызывается `launchAuth(pkg|null)`.
5. Отпускание клавиши с `HOLD` в конце буфера очищает буфер.

`HotkeyConfig` кэшируется в сервисе и перечитывается из Room только после `stopRecording()` (флаг `cachedHotkeyConfigStale`).

```java
private void launchAuth(String targetPackage) {
    Intent intent = new Intent(this, AuthActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    if (targetPackage != null) {
        intent.setData(Uri.parse("lokker://launch/" + targetPackage));
        intent.putExtra("target_package", targetPackage);
    }
    intent.putExtra("from_hotkey", true);   // учитывает hotkeyUntilClosed
    startActivity(intent);
}
```

### Режим записи

`startRecording(listener)` / `stopRecording()` вызываются из `HotkeySetupActivity`. В этом режиме обработка синхронная, а клавиши **поглощаются** (кроме `BACK`). Слушатель получает коды с тем же кодированием, что и в обычном режиме, а `HotkeySetupActivity.upgradeRecordedKey()` собирает из них итоговую последовательность.

| Тип горячей клавиши | По умолчанию | Настраиваемая |
|---|---|---|
| Открыть Lokker | **Нет** (обязательна перед самоскрытием) | Да |
| Открыть приложение N | Нет | Да, для каждого приложения + флаг «до закрытия» |
| Код номеронабирателя | `*#*#565537#*#*` | Нет (задан в манифесте) |

> **Предупреждение:** надёжнее всего клавиши громкости. Долгое нажатие кнопки питания на Android 12+ открывает системное меню.

> **История исправлений (см. git log):** проверены и исправлены сценарии «короткое / долгое / двойное / тройное нажатие», «удержание + 1–4 клавиши», последовательности; «long key1, long key2»; ввод PIN во время удержания горячей клавиши; ускорение обработки (быстрый путь, executor) и подавление автоповторов при удержании.

---

## 10. Слой данных

### База данных Room (`lokker_database`, версия 2)

```java
@Entity(tableName = "lokker_apps")
public class LokkerApp {
    @PrimaryKey @NonNull public String packageName;
    public String appLabel;
    @TypeConverters(Converters.class)
    public List<Integer> hotkeySequence;   // nullable; хранится как JSON-строка
    public boolean hidden;                 // true = скрыто на системном уровне
    public long addedAt;
    public boolean hotkeyUntilClosed;      // v2: горячая клавиша раскрывает «до закрытия»
}

@Entity(tableName = "hotkey_map")
public class HotkeyMap {
    @PrimaryKey public int id = 1;         // одна строка
    @TypeConverters(Converters.class)
    public List<Integer> lokkerHotkey;
}
```

**Миграция 1→2:** `ALTER TABLE lokker_apps ADD COLUMN hotkeyUntilClosed INTEGER NOT NULL DEFAULT 0`.

`LokkerAppDao`: `getAll()` / `getAllLive()` / `getAllHidden()` (сортировка по `appLabel ASC`), `get`, `isManaged`, `isHidden`, `insert` (REPLACE), `setHidden`, `setHotkey(pkg, String json)`, `setHotkeyUntilClosed`, `delete`, `deleteAll`.
`HotkeyMapDao`: `get()`, `insertOrUpdate()`.

### EncryptedSharedPreferences (`LokkerPrefs`)

Синглтон, файл `lokker_secure_prefs`, MasterKey AES256_GCM, ключи AES256_SIV, значения AES256_GCM. Есть типизированные методы для boolean и String, остальное доступно через `getPrefs()`.

| Ключ | Тип | Назначение |
|---|---|---|
| `pw_hash` | String | `Base64(salt):Base64(hash)` PBKDF2 |
| `fail_count` | String (число) | Счётчик неудачных попыток |
| `lockout_until` | String (мс) | Время окончания блокировки |
| `biometric_enrolled` | boolean | Переключатель «Биометрия» (по умолчанию false) |
| `pending_rehide` | StringSet | Временно раскрытые пакеты |
| `pending_rehide_on_close` | StringSet | Пакеты в режиме «до закрытия» |
| `unhide_all_snapshot` | String (JSON-массив) | Снимок для «Hide all apps» |

> Состояние самоскрытия в настройках не хранится (см. Раздел 5). Устаревшие ключи `self_hidden` и `stealth_mode` могут остаться на обновлённых устройствах, но не читаются.

### Файлы

- `files/icons/{package}.png` — кэш иконок.

---

## 11. Система сборки и развёртывание

### Gradle-сборка APK

- Gradle 8.8, AGP 8.2.2, `compileSdk 35`, `targetSdk 35`, `minSdk 26`, исходники Java 17. Собирается и на JDK 17, и на JDK 21: AGP 8.2.0 на JDK 21 падал в `JdkImageTransform`/jlink.
- Зависимости: appcompat 1.6.1, recyclerview 1.3.2, material 1.11.0, room 2.6.1 (annotationProcessor), security-crypto 1.1.0-alpha06, biometric 1.2.0-alpha05, lifecycle-viewmodel/livedata 2.7.0, core 1.12.0.
- `versionName` берётся из `app/build.gradle` (сейчас `1.2b`), имя артефакта — `Lokker-v{versionName}.apk`. `versionName` в манифесте (1.0) переопределяется Gradle.
- Подпись: `keystore3.jks`, alias `key0` (это **не** платформенный ключ). Путь ищется по порядку: `-PlokkerKeystore=<путь>`, переменная окружения `LOKKER_KEYSTORE`, `D:/Programming/keystores/keystore3.jks` (Windows), `/data/@SIGNING-KEYS/keystore3.jks` (Linux). Если ничего не найдено, сборка останавливается с понятным сообщением. При встраивании через `android_app_import` с `certificate: "platform"` система сборки переподписывает APK платформенным ключом прошивки. Без этого signature-разрешения вроде `MANAGE_USERS` выданы не будут.

```bash
./gradlew assembleRelease
# → app/build/outputs/apk/release/Lokker-v1.2b.apk
```

### Встраивание в образ LineageOS (`deployment/`)

Готовый APK кладётся рядом с `deployment/Android.bp` под именем `Lokker.apk`. Модуль импортирует его без компиляции:

```
android_app_import {
    name: "Lokker",
    apk: "Lokker.apk",
    certificate: "platform",
    privileged: true,
    system_ext_specific: true,
    dex_preopt: { enabled: false },
    required: ["com.lokker.app.privapp-permissions"],
}

prebuilt_etc {
    name: "com.lokker.app.privapp-permissions",
    system_ext_specific: true,
    sub_dir: "permissions",
    src: "com.lokker.app.privapp-permissions.xml",
    filename_from_src: true,
}
```

В `device.mk`: `PRODUCT_PACKAGES += Lokker`. Результат — `/system_ext/priv-app/Lokker/Lokker.apk` и `/system_ext/etc/permissions/com.lokker.app.privapp-permissions.xml`.

### com.lokker.app.privapp-permissions.xml

```xml
<permissions>
    <privapp-permissions package="com.lokker.app">
        <permission name="android.permission.MANAGE_USERS"/>
        <permission name="android.permission.CHANGE_COMPONENT_ENABLED_STATE"/>
        <permission name="android.permission.REMOVE_TASKS"/>
        <permission name="android.permission.REAL_GET_TASKS"/>
        <permission name="android.permission.WRITE_SECURE_SETTINGS"/>
        <permission name="android.permission.MANAGE_ACTIVITY_STACKS"/>
    </privapp-permissions>
</permissions>
```

### Ручная установка (adb, root)

```bash
adb root && adb remount
adb push Lokker-v1.2b.apk /system/system_ext/priv-app/Lokker/Lokker.apk
adb push deployment/com.lokker.app.privapp-permissions.xml /system/system_ext/etc/permissions/
adb reboot
```

---

## 12. Структура проекта

```
Lokker/
├── SPEC.md                      # этот документ (RU)
├── Readme.md                    # краткое описание (EN)
├── mockup.html                  # HTML-макет интерфейса
├── build.gradle / settings.gradle / gradle.properties / gradlew
├── deployment/
│   ├── Android.bp               # android_app_import + prebuilt_etc
│   └── com.lokker.app.privapp-permissions.xml
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── drawable/ic_launcher.xml
        │   ├── menu/context_menu.xml        # ⋮-меню главного экрана (UI строится в коде, layout/ нет)
        │   ├── values/   (strings.xml EN, colors.xml, styles.xml)
        │   ├── values-ru/strings.xml
        │   └── xml/accessibility_service_config.xml
        └── java/com/lokker/app/
            ├── LokkerApp.java                    # Application: hidden_api_policy, авто-включение a11y, recoverLeakedApps
            ├── receiver/
            │   ├── BootReceiver.java             # BOOT_COMPLETED → повторное скрытие
            │   ├── PackageMonitor.java           # PACKAGE_REPLACED/ADDED
            │   ├── SecretCodeReceiver.java       # *#*#565537#*#*, *#*#5655377469#*#*
            │   └── ScreenReceiver.java           # SCREEN_ON (регистрируется в сервисе)
            ├── service/
            │   └── LokkerAccessibilityService.java  # передний план, повторное скрытие, «до закрытия», горячие клавиши, запись
            ├── ui/
            │   ├── MainActivity.java             # список, поиск, ⋮-меню, FAB
            │   ├── AuthActivity.java             # PIN + биометрия + запуск цели
            │   ├── SettingsActivity.java
            │   ├── ChangePasswordActivity.java
            │   ├── HotkeySetupActivity.java
            │   ├── AppPickerDialog.java          # Activity выбора приложений
            │   └── CreateShortcutActivity.java   # ACTION_CREATE_SHORTCUT
            │   (адаптеры RecyclerView встроены в активности)
            ├── domain/
            │   ├── AppRepository.java            # единый источник истины (синглтон)
            │   ├── AuthManager.java              # PBKDF2, счётчик, блокировка
            │   └── HotkeyManager.java            # буфер, кодирование нажатий, HotkeyConfig
            └── data/
                ├── LokkerPrefs.java
                └── db/ (LokkerDatabase, LokkerApp, HotkeyMap, LokkerAppDao, HotkeyMapDao, Converters)
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
| Первый запуск Lokker (пароль не установлен) | Lokker открывается без аутентификации → пустой главный экран | `AuthActivity` проверяет `pw_hash` — отсутствует → сразу `MainActivity` |
| Пользователь устанавливает пароль в настройках | Пароль сохранён, следующий запуск требует аутентификации | `ChangePasswordActivity` в режиме создания → `AuthManager.setPassword()` |
| Пользователь нажимает горячую клавишу Lokker | Запрос PIN/биометрии → открывается интерфейс Lokker | `onKeyEvent` → `checkHotkeys()` → `AuthActivity` → `MainActivity` |
| Пользователь набирает `*#*#565537#*#*` | То же, что горячая клавиша | `SecretCodeReceiver` → `AuthActivity` |
| Пользователь набирает `*#*#5655377469#*#*` | **Все** скрытые пакеты устройства раскрыты, без аутентификации; Toast «Lokker: all apps unhidden» | `unhideAllSystemWide()` |
| Пользователь открывает Lokker повторно (после Home) | Снова запрашивается PIN | `MainActivity.onStop()` сбрасывает `authenticated` |
| Пользователь запускает скрытое приложение из списка Lokker | Приложение запускается (PIN уже введён) | `unhideTemporarily()` → `launchHiddenApp()` |
| Горячая клавиша приложения | PIN → приложение запускается | `AuthActivity` → `unhideTemporarily()` или `unhideUntilClosed()` (если `hotkeyUntilClosed`) |
| **Пользователь уходит с временно раскрытого приложения** (Home, Назад, Недавние, другое приложение) | **Приложение сразу скрывается и удаляется из недавних** | **`TYPE_WINDOW_STATE_CHANGED` → `rehideApp()` + `removeFromRecents()`** |
| Появилась клавиатура / шторка в скрытом приложении | Приложение **не** скрывается | IME и `com.android.systemui` игнорируются |
| Приложение в режиме «до закрытия», пользователь переключается | Приложение остаётся видимым и в недавних | Нет `EXCLUDE_FROM_RECENTS`, нет повторного скрытия по фокусу |
| Приложение в режиме «до закрытия», пользователь смахивает задачу | Приложение скрывается (в пределах ~3 с) | `onTaskRemoved` / опрос 3 с → `checkRehideOnClose()` → `rehideClosedApp()` |
| Скрытое приложение пытается отправить уведомление | **Невозможно** — скрытое приложение не может работать | `setApplicationHiddenSetting` блокирует запуск всех компонентов |
| Скрытое приложение обновлено системой | Остаётся скрытым | Система сохраняет состояние; `PackageMonitor` повторно применяет скрытие |
| Lokker переустановлен/обновлён | Переключатель самоскрытия совпадает с фактической видимостью иконки | `isSelfHidden()` читает состояние компонента alias |
| Устройство перезагружается | Все скрытые приложения остаются скрытыми | `packages.xml`; `BootReceiver` повторно применяет скрытие |
| Экран включается | Состояние скрытия перепроверено | `ScreenReceiver` (SCREEN_ON) |
| Процесс Lokker убит во время раскрытия (любого режима) | **Приложение повторно скрывается при следующем старте процесса** | `pending_rehide` / `pending_rehide_on_close` → `recoverLeakedApps()` |
| Пользователь добавляет приложения через экран выбора | Приложения добавлены **и сразу скрыты** | `addApplication()` + `hideApp()` |
| ⋮ → «Remove from Lokker» | Подтверждение → приложение раскрыто и удалено из Lokker | `removeApplication()` |
| ⋮ → «Unhide until closed» | Приложение запускается в режиме «до закрытия» | `unhideUntilClosed()` → `launchHiddenApp(pkg, false)` |
| Переназначатель (KeyMapper) вызывает ярлык Lokker | PIN → временное раскрытие → запуск → повторное скрытие | `CreateShortcutActivity`-intent → `AuthActivity` (`lokker://launch/{pkg}`) |
| Настройки → «Unhide all apps» | Все скрытые приложения раскрыты, остаются в Lokker; снимок сохранён | `unhideAll()` |
| Настройки → «Hide all apps» | Приложения из снимка снова скрыты | `rehideAll()` (пропускает удалённые из Lokker и с устройства) |
| Включение самоскрытия без горячей клавиши | Отказ, Toast «Set up a hotkey first» | `setSelfHidden()` → `IllegalStateException` |
| 5 неверных PIN подряд | Блокировка на 30 с с обратным отсчётом | `AuthManager.setLockout()` |

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
    <uses-permission android:name="com.android.launcher.permission.INSTALL_SHORTCUT"/>
</manifest>
```

> **Примечания по разрешениям:**
> - `MANAGE_USERS` — требуется для `setApplicationHiddenSetting()` (основной API скрытия)
> - `CHANGE_COMPONENT_ENABLED_STATE` — используется только для самоскрытия Lokker (activity-alias)
> - Объявление `NotificationListenerService` не требуется — скрытые приложения не могут отправлять уведомления
> - `WRITE_SECURE_SETTINGS` — автоматическое включение Accessibility-сервиса и `hidden_api_policy`
> - `GET_TASKS` и `INSTALL_SHORTCUT` объявлены в манифесте, но в privapp-XML их нет. `INSTALL_SHORTCUT` текущим кодом не используется

---

## 15. Фазы разработки

> **Статус:** все фазы выполнены. Релизы отмечены git-тегами:
> - `Lokker-v1.0` (8bd7837);
> - `Lokker-v1.1` (125aecf): режим «до закрытия», удаление динамических ярлыков, жесты горячих клавиш (долгое/двойное/тройное/удержание);
> - `Lokker-v1.2a` (60245e6): ускорение обработки горячих клавиш и подавление автоповторов.
>
> - v1.2b: развёртывание через `android_app_import` (f23f430); исправлено состояние переключателя самоскрытия (теперь читается из компонента `LokkerLauncher`); удалён неиспользуемый код (импорт/экспорт, ViewModel, адаптеры, layout-файлы).
>
> Ниже исходный план, сохранён для истории.

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
- `AuthActivity`: интерфейс ввода PIN + резервная биометрия + обработка неверного PIN (тряска, подсветка) + блокировка
- `ChangePasswordActivity`: два режима — создание пароля (2 поля) и смена (3 поля) с валидацией
- Логика блокировки: 5 неудач → тайм-аут 30 секунд с таймером обратного отсчёта
- Защита `MainActivity` аутентификацией при запуске (пропускается, если пароль не установлен)

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
