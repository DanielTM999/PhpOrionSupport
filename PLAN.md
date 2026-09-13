# Wizard de projeto PHP/Composer + auto-trigger de completion

## Context

O `PhpOrionSupport` é um plugin da Orion IDE que hoje declara **um único plugin** (`php-ide-adapter`) e **não tem wizard de criação de projeto** — todos os plugins irmãos (`JavaOrionSupport`, `DotnetOrionSupport`, `ClangOrionSupport`, `ArduinoOrionSupport`) implementam `ProjectWizardProvider`, então "New Project" da IDE não oferece nada para PHP.

Em paralelo, o completion de PHP dispara o tempo todo. A causa é a combinação de dois pontos em `src/main/java/dtm/ide/PhpIdeAdapter.java`:
- `isAutoCompletionOnTypingEnabled()` → `true` (linha 559) habilita, no host, o caminho `CodeEditorServiceImpl.isAdapterTypingTrigger` que retorna `true` para **toda letra, dígito e `_`**;
- `shouldAutoTriggerCompletion()` → `return true` para qualquer arquivo PHP (linha 554), ou seja, o único veto que o plugin controla está desligado;
- `getCompletionTriggerCharacters()` (linha 565) ainda soma `-`, `"`, `'`, `/`, `<`, `@`, `#` aos triggers do Intelephense, o que dispara dentro de strings, comentários e HTML.

Resultado esperado: wizard PHP equivalente ao Java (mesmos steps, mesma UI, mesmos componentes SwingTools), e popup de completion que só aparece em `$`, `->`, `::`, `\`, `#[` ou Ctrl+Espaço.

---

## Regras de execução

**Passo 0 — primeira coisa a fazer:** escrever este plano em `PLAN.md` na raiz do projeto (`C:\Users\danie\Documents\development\java\PhpOrionSupport\PLAN.md`), antes de qualquer alteração de código. Só depois começar a Parte 1.

**Sem comentários no código.** Nenhum arquivo Java novo ou modificado pode conter comentários — nem `//`, nem `/* */`, nem Javadoc `/** */`. Os nomes de classes, métodos e variáveis devem carregar a intenção sozinhos. Isso vale inclusive ao portar `WizardUi`, `WizardFormGrid`, `PillButtons` e `WizardIcons` do `JavaOrionSupport`: copiar removendo os comentários existentes (ex.: o Javadoc de `HtmlCompletionProvider` é referência, não modelo a replicar).

Exceção única: as strings de template geradas pelo `PhpProjectScaffolder` (conteúdo dos arquivos `.php`, `composer.json`, `.htaccess`, `.gitignore` criados no projeto do usuário) podem conter comentários próprios da linguagem-alvo — isso é conteúdo de dados, não código-fonte do plugin.

---

## Parte 1 — Auto-trigger do completion

Arquivo: `src/main/java/dtm/ide/PhpIdeAdapter.java`, linhas 553-568.

Decisão do usuário: **só caracteres de trigger**.

1. `isAutoCompletionOnTypingEnabled()` permanece `true`. Verificado no host: `CodeEditorServiceImpl.isAdapterTypingTrigger` (linha 1590) retorna cedo quando isso e `false`, o que mataria tambem `$`, `->` e `::`. O gate so abre para os trigger chars quando este metodo e `true`; o veto fino fica no item 3, que o host consulta depois em `IdeAdapterEditorProvider.shouldAutoTrigger` (linhas 174-186).
2. `getCompletionTriggerCharacters()` → reduzir o set hardcoded para `Set.of('$', '>', ':', '\\', '#')` e **parar de unir** `lsp.completionTriggers()` cegamente: filtrar os triggers do Intelephense pelo mesmo conjunto permitido, para o LSP não reintroduzir `-`/`"`/`'`. Manter `<` e `/` **apenas** quando o arquivo for hibrido e o caret estiver em região HTML — na prática, como o set é global, mantê-los fora e deixar o HTML abrir por Ctrl+Espaço (ver item 3).
3. `shouldAutoTriggerCompletion(IdeCompletionContext)` deixa de ser `return true`. Novo corpo, espelhando `OrionWebkit/.../WebkitAdapter.java:774-795` (que já faz exatamente esse filtro e serve de referência):
   - `false` se `context == null` ou `!PhpProjectConventions.isPhp(context.filePath())`;
   - lê o char digitado de `context.currentLine().charAt(caretCol - 1)` (guardas de bounds como no Webkit);
   - `false` se `context.triggerKind() == IdeCompletionTriggerKind.EXPLICIT` **não** se aplica — EXPLICIT (Ctrl+Espaço) não passa por aqui; este método só filtra digitação;
   - `false` se o caret estiver em string ou comentário PHP — novo helper (ver item 4);
   - em região não-PHP (`!HtmlCompletionProvider.isPhpAt(text, caretOffset)`): só `<` e `/` disparam, e nunca dentro de valor de atributo (portar `insideHtmlAttributeValue` do `WebkitAdapter`, linhas 825-844);
   - em região PHP: `true` só se o char for `$`, `\`, `#`, ou se fechar uma sequência `->` / `::` (checar `charAt(col-2)` para `-` antes de `>` e `:` antes de `:`). Um `>` solto (fim de tag HTML, comparação `a > b`) e um `:` solto (ternário, `case x:`, tipo de retorno) **não** disparam.
4. Novo helper estático em `src/main/java/dtm/ide/editor/` — `PhpCaretContext.java` (classe nova, ~80 linhas), com `static boolean inStringOrComment(String text, int offset)`: varre do último `<?php`/`<?=` antes do caret até o offset, mantendo estado de `'...'`, `"..."`, heredoc/nowdoc, `//`, `#` de linha e `/* */`, respeitando escapes. Importante: `#` só é comentário se **não** for seguido de `[` (atributo PHP 8), senão os snippets Origins `#[Controller]` param de funcionar.
   - Nota: `#[` deve continuar disparando; então `inStringOrComment` retorna `false` para o caret logo após `#` quando o próximo char é `[` ou quando o `#` inicia um atributo.

Efeito colateral positivo: `getCompletionSuggestions` continua igual — o Ctrl+Espaço (`IdeCompletionTriggerKind.EXPLICIT`) segue entregando HTML e LSP normalmente; só o disparo automático fica restrito.

---

## Parte 2 — Wizard de projeto PHP

Novo pacote `src/main/java/dtm/ide/wizard/`, espelhando `JavaOrionSupport/src/main/java/dtm/ide/wizard/`.

### 2.1 Registro

- **`PhpProjectWizardProvider.java`** — `@Singleton @PluginReference(id = "php-project-wizard", singleton = true, scope = PluginScope.APPLICATION) extends ProjectWizardProvider`. `getProjectWizards()` devolve um `PhpProjectWizard` por template (ver 2.2).
- **`src/main/resources/META-INF/plugin-configs.json`** — adicionar a segunda entrada no array `plugins`:
  ```json
  { "id": "php-project-wizard", "name": "PHP Project Wizard",
    "description": "Wizard para criação de projetos PHP, Composer, Origins, Laravel, Symfony e Slim",
    "version": "1.0.0", "website": "https://github.com/DanielTM999/PhpOrionSupport" }
  ```
  (O `pluginPackages: ["dtm.ide"]` já cobre o novo pacote — a descoberta é por scanning de `@PluginReference`.)

### 2.2 `PhpTemplate.java` (enum)

| Constante | Nome exibido | Geração |
|---|---|---|
| `ORIGINS_APPLICATION` | Aplicação Origins | local |
| `COMPOSER_APPLICATION` | Aplicação Composer | local |
| `COMPOSER_LIBRARY` | Biblioteca Composer | local |
| `PLAIN_PHP` | PHP simples | local |
| `LARAVEL` | Laravel | `composer create-project laravel/laravel` |
| `SYMFONY` | Symfony | `composer create-project symfony/skeleton` |
| `SLIM` | Slim | `composer create-project slim/slim-skeleton` |

Flags: `usesComposer()` (todos menos `PLAIN_PHP`), `needsCreateProject()` (os 3 últimos, com `createProjectPackage()`), `isOrigins()`, `hasEntryPoint()`.

### 2.3 `PhpProjectWizardView.java` (a UI)

`final class PhpProjectWizardView extends JPanel` com `CardLayout` + `StepsPanel`, 720x560 via `UiTokens.scale` — mesma estrutura de `JavaProjectWizardView.java`. Todos os componentes já existem em SwingTools (confirmado): `StepsPanel`, `MaskedTextField`, `DropdownFieldListener`, `SegmentedField`, `TagInputField`, `DualListField`, `FormField`, `AlertPanel`, `CardPanel`, `ProgressBar`.

**Steps:**
1. **Projeto** (sempre) — `location` (`MaskedTextField` + `JFileChooser` DIRECTORIES_ONLY), `projectName`, `phpVersion` (`DropdownFieldListener<String>`: `8.4`, `8.3`, `8.2`, `8.1`, `8.0`; default `8.3`), `namespace`.
2. **Composer** (se `usesComposer()`) — `vendor` (default `vendor`), `packageName`, `version` (default `1.0.0`), `description`, `type` (`SegmentedField`: `project`/`library`), `license` (default `MIT`).
3. **Dependências** (se `usesComposer()`) — `DualListField<Package>` alimentado pelo Packagist (ver 2.5), com filtro e contadores, + `SegmentedField` require/require-dev.
4. **Revisão** (sempre) — resumo + checkbox **"Executar composer install"** (marcado por padrão, decisão do usuário).

**Derivação automática** (portar `wireDerivedFields()`): digitar `projectName` preenche `packageName` (kebab-case) e `namespace` (`Vendor\ProjectName` PascalCase), salvo se o usuário já editou o campo (flags `packageEdited`/`namespaceEdited` detectadas via `hasFocus()`).

**Validação** (regex, análogas às de `JavaProjectWizardView.java:62-66`):
- `NAMESPACE = "[A-Za-z_\\x80-\\xff][A-Za-z0-9_\\x80-\\xff]*(\\\\[A-Za-z_\\x80-\\xff][A-Za-z0-9_\\x80-\\xff]*)*"`
- `PACKAGE_NAME = "[a-z0-9]([_.-]?[a-z0-9]+)*/[a-z0-9](([_.]|-{1,2})?[a-z0-9]+)*"` (regra oficial do Composer)
- `VERSION = "[0-9]+(\\.[0-9]+)*([-+][A-Za-z0-9.]+)?"`
- `FOLDER_NAME = "[^\\\\/:*?\"<>|]+"`
- Reusar a lógica de `validateDestination()` (pasta pai existe e é diretório; nome não vazio/`.`/`..`; destino não é arquivo; se a pasta existir tem que estar **vazia**). Erros no `AlertPanel` + `FormField.setError` + foco no campo.

**Criação** — thread daemon `"php-project-wizard"` com `ProgressBar` indeterminada; ao fim `callback.notifyProjectCreated(created)` via `SwingUtilities.invokeLater`. Fluxo:
```
Path created = template.needsCreateProject()
        ? createProjectViaComposer(directory)   // composer create-project
        : PhpProjectScaffolder.create(request); // geração local
if (runComposerInstall && template.usesComposer() && !template.needsCreateProject())
    composer install   // create-project já instala
```
Falha do composer **não** invalida o projeto já gerado: mostra `AlertPanel` WARNING e ainda assim notifica a criação.

### 2.4 `PhpProjectScaffolder.java` (geração local)

`public record ProjectRequest(Path directory, PhpTemplate template, String vendor, String packageName, String version, String description, String namespace, String phpVersion, String license, List<Dependency> dependencies)` com normalização de defaults no compact constructor (padrão de `JavaProjectScaffolder.ProjectRequest`), e `public static Path create(ProjectRequest) throws IOException`.

Text blocks Java inline com `.formatted(...)` — sem arquivos de template em disco, igual ao Java.

**`COMPOSER_APPLICATION` / `COMPOSER_LIBRARY`:**
```
composer.json      name, description, type, license, require { "php": "^<phpVersion>" } + escolhidas,
                   require-dev { "phpunit/phpunit": "^11.0" },
                   autoload { "psr-4": { "<Namespace>\\": "src/" } },
                   autoload-dev { "psr-4": { "<Namespace>\\Tests\\": "tests/" } },
                   scripts { "test": "phpunit" }, config { "sort-packages": true }
src/<Nome>.php     classe com namespace e declare(strict_types=1)
tests/<Nome>Test.php  PHPUnit\Framework\TestCase
phpunit.xml        testsuite apontando para tests/
.gitignore         /vendor/ /.orion/ composer.lock (só p/ APPLICATION) .env .phpunit.result.cache
public/index.php   só para APPLICATION (require vendor/autoload.php)
```

**`ORIGINS_APPLICATION`** — o que o `PhpProjectConventions.describe()` já reconhece como `ORIGINS_APPLICATION` (`isOriginsRoot` + `isApplicationRoot`):
```
composer.json      require { "php", "danieltm/origins" } + autoload PSR-4
modules.config     declaração dos módulos (campo TagInputField no step Composer, default ["app"])
serverroutes.json  rotas iniciais
index.php          require vendor/autoload.php + Origin::initialize(...)
.htaccess          RewriteEngine para index.php
src/<Modulo>/...   controller de exemplo com #[Controller] e #[Get]
.gitignore
```
Aproveitar `dtm.ide.origins.OriginsIndexer` / `OriginsModule` como referência do formato exato de `modules.config` e `serverroutes.json` ao escrever os templates.

**`PLAIN_PHP`:** `index.php` (hello world) + `src/` + `.gitignore` (`/.orion/`).

### 2.5 `PackagistClient.java` (análogo do `SpringInitializrClient`)

`BASE_URL = "https://packagist.org"`, `HttpClient` do JDK, timeout 30s, `User-Agent: PhpOrionSupport/1.0`, Jackson 3 (`tools.jackson`, já no pom).

- `public List<Package> search(String query)` → `GET /search.json?q=<q>&per_page=30`, parse de `results[]` em `record Package(String name, String description, long downloads)`.
- `public Optional<String> latestVersion(String packageName)` → `GET /p2/<name>.json`, primeira versão estável (descartar `dev-*`).
- Falha de rede **nunca lança**: retorna lista vazia e a view mostra `AlertPanel` WARNING com botão "Tentar novamente" — exatamente o `showOfflineAlert` de `JavaProjectWizardView.java:646`.
- Busca em thread daemon `"packagist-search"`, com debounce no campo de filtro do `DualListField`.

### 2.6 Composer auto-instalável nos resources

Pedido explícito: *"se não tiver composer instalado ele instala nos resources"*.

Novo **`src/main/java/dtm/ide/composer/ComposerInstallerService.java`**, seguindo exatamente o padrão de `PhpDebugAdapterInstallerService.java` (download HTTP + instalação sob `resourcesRoot.resolve("php")`):
```java
public static final String VERSION = "2.8.4";
static final URI DOWNLOAD = URI.create("https://getcomposer.org/download/" + VERSION + "/composer.phar");

@Async
public CompletableFuture<List<String>> ensure(Path resourcesRoot, PhpToolchainService toolchain)
```
- Se `toolchain.findComposer() != null` → devolve `List.of(composer.toString())` (usa o do sistema).
- Senão: baixa `composer.phar` para `resourcesRoot/php/composer-<VERSION>/composer.phar` (validar `statusCode/100 == 2` e tamanho mínimo, como o installer do DAP faz) e devolve `List.of(toolchain.findPhp(null).toString(), phar.toString())` — roda o `.phar` pelo PHP, sem precisar de `composer.bat`.
- `resourcesRoot` vem de `getResource().getResourcePath()` — disponível no provider porque `ProjectWizardProvider extends PluginContext` (mesmo acesso usado em `PhpIdeAdapter.java:1148`).

**`ComposerService.execute` precisa aceitar comando com múltiplos tokens.** Hoje a assinatura é `execute(Path composer, Path wd, OutputStream, String... args)` e monta `command.add(composer.toString())`. Adicionar uma sobrecarga `execute(List<String> launcher, Path wd, OutputStream, String... args)` e fazer a assinatura atual delegar para ela — mantém `PhpIdeAdapter.java:1399` funcionando sem alteração.

### 2.7 UI helpers e ícones

- Portar `WizardUi.java`, `WizardFormGrid.java` e `PillButtons.java` de `JavaOrionSupport` (`dtm.ide.ui.PillButtons` é local daquele plugin, não vem do SwingTools — copiar para `src/main/java/dtm/ide/ui/PillButtons.java`).
- **`WizardIcons.java`** + novos SVGs em `src/main/resources/imgs/` (a pasta hoje só tem `codeActionLampRed.svg` / `codeActionLampYellow.svg`): `wizard-php.svg`, `wizard-composer.svg`, `wizard-origins.svg`, `wizard-laravel.svg`, `wizard-symfony.svg`, `wizard-slim.svg`, e os tingidos `wizard-folder/search/create/cancel/dependency/loading/error.svg`. Carregar com `ImageUtils.getIconByResource` / `getColoredIconByResource`, idêntico a `JavaOrionSupport/.../WizardIcons.java`.

### 2.8 i18n

Adicionar chaves `PhpProjectWizardView.*` nos **6** arquivos de `src/main/resources/languages/` (`pt-BR`, `en-US`, `es-ES`, `fr-FR`, `it-IT`, `de-DE`), resolvidas por `I18n.getText(PhpProjectWizardView.class, key, fallback)` com fallback pt-BR inline em todo literal — mesma convenção já usada pelo `PhpIdeAdapter`.

---

## Arquivos

**Novos** — `src/main/java/dtm/ide/wizard/{PhpProjectWizardProvider,PhpProjectWizard,PhpProjectWizardView,PhpProjectScaffolder,PackagistClient,PhpTemplate,PhpFileTemplates,WizardUi,WizardFormGrid,WizardIcons}.java`, `src/main/java/dtm/ide/ui/PillButtons.java`, `src/main/java/dtm/ide/composer/ComposerInstallerService.java`, `src/main/java/dtm/ide/editor/PhpCaretContext.java`, SVGs em `src/main/resources/imgs/`.

**Modificados** — `src/main/java/dtm/ide/PhpIdeAdapter.java` (linhas 553-568), `src/main/java/dtm/ide/composer/ComposerService.java` (sobrecarga), `src/main/resources/META-INF/plugin-configs.json`, os 6 `languages/*.json`.

---

## Verificação

**Testes** (JUnit 5 já no pom, `src/test/java/dtm/ide/`):
- `PhpProjectScaffolderTest` — gerar cada template local em `@TempDir` e afirmar que `PhpProjectConventions.describe()` devolve o `PhpProjectKind` esperado (`ORIGINS_APPLICATION` para o template Origins, `PHP_APPLICATION`/`PHP_LIBRARY` para os Composer). É o mesmo contrato que `JavaProjectScaffolderTest` usa — valida o scaffolder contra o próprio detector do plugin. Afirmar também que o `composer.json` gerado é JSON válido e tem `autoload.psr-4`.
- `PhpProjectWizardViewTest` — instanciar a view headless por template e varrer a árvore de componentes por `FormField` (`location`, `packageName`, `vendor`, `version`, `namespace`) e contar steps: 2 para `PLAIN_PHP`, 4 para os Composer (espelha `JavaProjectWizardViewTest`).
- `PhpCaretContextTest` — casos de string/heredoc/comentário/`#[`; e `shouldAutoTriggerCompletion` retornando `false` para letra, `"`, `'`, `-`, `>` solto, `:` solto, e `true` para `$`, `->`, `::`, `\`, `#[`.

**Manual** (`mvn -q package` já copia o JAR para `%APPDATA%\Roaming\Orion\plugins` via antrun; reiniciar a Orion):
1. New Project → aba PHP deve listar os 7 wizards com ícones.
2. Gerar um "Aplicação Composer" em pasta vazia com 1-2 deps do Packagist e `composer install` marcado → conferir `vendor/` criado e o projeto abrindo com o adapter PHP ativo.
3. Renomear/esconder o `composer` do PATH e repetir → o `composer.phar` deve ser baixado para `%APPDATA%\Roaming\Orion\...\php\composer-2.8.4\` e usado.
4. Gerar "Aplicação Origins" → painel Origins deve indexar o módulo e a rota de exemplo.
5. Sem rede no step de dependências → alerta "Tentar novamente", e o wizard ainda deve concluir.
6. Abrir um `.php` e digitar `function calc` → **nenhum** popup. Digitar `$`, `->`, `::`, `\`, `#[` → popup. Ctrl+Espaço em qualquer posição → popup. Digitar dentro de `"..."` ou `// comentário` → nenhum popup.
