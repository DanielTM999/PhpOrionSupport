# PHP Orion Support

Adapter nativo de PHP para a Orion IDE, com suporte ao framework Origins, Composer, Intelephense e Xdebug.

O plugin funciona de forma independente para projetos PHP e coopera com o Orion Webkit nas regiões HTML de arquivos híbridos, como páginas `.php` e `.phtml`.

## Recursos

### Editor PHP

- Highlight de PHP, HTML embutido e atributos PHP, incluindo argumentos e o fechamento `]`.
- Autocomplete de PHP pelo Intelephense.
- Autocomplete de tags e atributos HTML fora dos blocos `<?php ... ?>`.
- Hover, assinatura de métodos, diagnósticos e formatação.
- Suporte a arquivos `.php`, `.phtml`, `.inc` e `.module`.
- Highlight específico para `.env`, `.env.*`, `.htaccess` e `modules.config`.

### Navegação e análise

- `Ctrl + clique` para ir à definição.
- **Ir para Definição**, **Ir para Implementação** e **Localizar Usos** no menu de contexto do editor.
- Navegação de interfaces e métodos de interface até suas implementações.
- Popup de seleção quando existem vários destinos.
- CodeLens com a quantidade de implementações e usos.
- A contagem de usos considera referências reais e ignora importações `use`, declarações, comentários, textos e arquivos de dependências.
- Índice PHP próprio como fallback, permitindo navegação mesmo antes de o servidor de linguagem terminar a inicialização.

### Origins

- Detecção automática de aplicações, bibliotecas e workspaces PHP.
- Reconhecimento do Origins por `modules.config`, pacotes Composer ou inicialização em `index.php`.
- Indexação dos módulos declarados em `modules.config`.
- Descoberta de controllers, endpoints e rotas definidas por atributos.
- Painel **PHP > Origins: módulos e endpoints**.
- Suporte a namespaces e projetos modulares dentro de `src`.

### Execução e depuração

O adapter disponibiliza as seguintes configurações:

- **PHP: XAMPP**
- **PHP: Servidor embutido**
- **PHP: Arquivo atual**
- **PHP: Escutar Xdebug**
- **PHP: Debug do arquivo atual**
- **PHP: Servidor embutido com Xdebug**

Também estão disponíveis comandos para continuar, avançar, entrar e sair durante uma sessão de depuração.

### Composer

O menu **PHP** permite executar:

- `composer install`
- `composer update`
- `composer dump-autoload`
- `composer validate`

## Requisitos

- Orion IDE 1.0.0 ou superior.
- JDK 25.
- Maven.
- Node.js 20 ou superior e npm no `PATH`.
- PHP instalado ou fornecido pelo XAMPP.
- Composer no `PATH` para usar as ações do Composer.

O Intelephense 1.18.5 é instalado automaticamente nos recursos da Orion na primeira inicialização. Essa etapa requer acesso à internet.

## Compilação e instalação

Feche a Orion antes de substituir o plugin, pois o arquivo JAR pode estar bloqueado enquanto a IDE estiver aberta.

Para compilar, executar os testes e instalar usando a configuração deste projeto:

```powershell
mvn clean package
```

O artefato gerado é:

```text
target\PhpOrionSupport-1.0.0.jar
```

Para gerar o JAR sem executar a cópia automática configurada no Maven:

```powershell
mvn -Dmaven.antrun.skip=true clean package
```

Depois, copie o arquivo para a pasta de plugins:

```powershell
New-Item -ItemType Directory -Path "$env:APPDATA\Orion\plugins" -Force
Copy-Item -LiteralPath ".\target\PhpOrionSupport-1.0.0.jar" `
    -Destination "$env:APPDATA\Orion\plugins\PhpOrionSupport-1.0.0.jar" -Force
```

Abra novamente a Orion após a instalação.

## Configuração

As opções ficam em **Configurações > PHP**:

- caminho do executável PHP;
- caminho do executável Composer;
- diretório do XAMPP;
- porta do Xdebug, com padrão `9003`;
- porta do servidor PHP, com padrão `8080`;
- abertura automática do navegador ao executar aplicações web.

Quando o caminho do PHP não é informado, a procura segue esta ordem:

1. Executável configurado nas opções.
2. `php\php.exe` dentro do diretório do XAMPP.
3. `C:\php\php_8.3_nts\php.exe`.
4. Executável `php` disponível no `PATH`.

## Como usar

1. Instale o plugin e abra uma pasta que contenha arquivos PHP ou `composer.json`.
2. Aguarde a inicialização e indexação do Intelephense.
3. Use `Ctrl + clique` sobre classes, interfaces, métodos e constantes para navegar.
4. Use o menu de contexto para localizar definições, implementações ou usos.
5. Clique nos CodeLens exibidos acima das declarações para abrir a lista de destinos.
6. Escolha uma configuração PHP na barra de execução para iniciar ou depurar o projeto.

Projetos com vários diretórios Composer em uma mesma raiz são tratados como workspace PHP.

## Xdebug

Use **PHP > Diagnosticar/instalar Xdebug** para verificar a instalação usada pelo executável PHP configurado.

Quando necessário, o plugin pode baixar a DLL compatível, criar backup do `php.ini`, configurar a extensão e validar o carregamento. Reinicie o Apache ou qualquer processo PHP ativo depois da instalação.

## Solução de problemas

### A navegação ainda não encontra um símbolo

- Aguarde o término da indexação inicial.
- Confira se a pasta aberta é a raiz do projeto ou do workspace.
- Use **PHP > Reiniciar Intelephense**.
- Verifique se o arquivo pertence ao projeto e não está dentro de `vendor`, `runtime`, `log` ou `node_modules`.

### O autocomplete PHP não inicia

- Confirme que `node` e `npm` estão disponíveis no `PATH`.
- Use Node.js 20 ou superior.
- Confira o indicador de status da Orion para ver a mensagem de erro do Intelephense.

### O projeto não executa

- Configure o executável PHP em **Configurações > PHP**.
- Para XAMPP, confirme que o diretório padrão é `C:\xampp` ou informe outro caminho.
- Verifique se as portas `8080` e `9003` estão livres ou altere-as nas configurações.

### O JAR não pode ser atualizado

Feche a Orion e repita a instalação. O script `scripts\install-after-orion-close.ps1` também pode aguardar a liberação do arquivo e concluir a substituição.

## Testes

Execute a suíte automatizada com:

```powershell
mvn test
```

Os testes cobrem tokenizer PHP/HTML, arquivos de configuração, autocomplete HTML, navegação, localização, reconhecimento de projetos Origins e instalação do Xdebug.

## Licença

Distribuído sob a licença MIT. Consulte [LICENSE](LICENSE).

Autor: Daniel Teixeira Melo  
Repositório: <https://github.com/DanielTM999/PhpOrionSupport>
