<div align="center">

<img src="packaging/assets/justinbeaver.png" alt="JustinBeaver" width="120" />

# JustinBeaver

**Um cliente de banco de dados desktop no estilo do DBeaver.**
Navegue, consulte e edite bancos via JDBC, com drivers baixados sob demanda.

![Java](https://img.shields.io/badge/Java-21-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6db33f) ![Vue](https://img.shields.io/badge/Vue-3-42b883) ![Electron](https://img.shields.io/badge/Electron-45-47848f) ![Plataformas](https://img.shields.io/badge/Linux%20%7C%20Windows-lightgrey)

</div>

---

## O que é

Backend **Spring Boot (Java 21)** + frontend **Vue 3 + TypeScript** + casca **Electron**.
Os drivers JDBC são declarados em arquivos YAML, baixados do Maven na primeira vez que são usados e carregados em um
class loader isolado cada. O instalador já leva Electron e um Java próprio: quem instala não precisa de mais nada.

## Funcionalidades

**Conexões**
- Mais de 90 drivers (PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, SQLite, H2, DuckDB, ClickHouse…) e drivers próprios via YAML
- Túnel SSH e SSL, tipos de conexão (DEV/PROD), modo somente leitura, pastas, importar conexões do DBeaver
- Seletor de Database/Schema em duas listas, como no DBeaver

**Editor SQL**
- Autocomplete (tabelas e colunas), formatação, plano de execução, histórico, scripts salvos, parâmetros
- Executar instrução, script ou a partir do cursor; cancelar; saída do servidor
- Palavras-chave em maiúsculas ao digitar (opcional), snippets, abrir/salvar arquivo `.sql`

**Tabelas e dados**
- Paginação, filtro (com histórico), ordenação, contagem, edição inline, visão de registro, DDL, navegação por chave estrangeira
- Menu de edição na célula (NULL, UUID, copiar da linha acima/abaixo) e **menu de cópia** (CSV, TSV, JSON, Markdown, SQL INSERT, HTML, XML, lista `IN`…)
- Exportar/importar dados, copiar tabela entre bancos, migrar e comparar dados e esquemas
- **Diagrama ER** com zoom, pan, layout automático e destaque de relações

**Ferramentas**
- Sessões, bloqueios, painel, usuários e papéis, backup/restauração, tarefas agendadas
- Barra de menus e botão direito completos (Arquivo, Editar, Navegar, Procurar, Editor SQL, Banco de dados, Janela, Ajuda), paleta de comandos (`Ctrl+Shift+P`)

**Aparência e conta**
- Cor de destaque e de fundo configuráveis, tema claro/escuro, interface em **português e inglês**
- Tela de carregamento ao abrir o app
- Salvar/importar **scripts e conexões no Google Drive** (criptografado com sua senha de sincronização)

O que existe em relação ao DBeaver, comando por comando: [docs/dbeaver-feature-map.md](docs/dbeaver-feature-map.md).

## Instalar (usuário final)

| Sistema | Arquivo | Como instalar |
|---|---|---|
| Ubuntu / Debian | `justinbeaver_<versão>_amd64.deb` | `sudo apt install ./justinbeaver_<versão>_amd64.deb` |
| Windows 10/11 | `JustinBeaver-Setup-<versão>.exe` | Duplo clique (instala só para o usuário) |

> No `apt install`, use `./` ou o caminho completo do arquivo, senão o apt procura um pacote com esse nome.

Os dados ficam em `~/.justinbeaver` (`%USERPROFILE%\.justinbeaver` no Windows) e são mantidos ao desinstalar.
Os drivers JDBC são baixados na primeira conexão de cada tipo de banco, então a primeira vez precisa de internet.

## Como subir (desenvolvimento)

Pré-requisitos: **JDK 21** e **Maven**. O build baixa o próprio Node em `frontend/node`.

```bash
git clone https://github.com/GuilhermeBorba2/justinBeaver.git
cd justinBeaver

mvn package                                        # Node local, build da UI, testes Java + UI, jar executável
java -jar target/justinbeaver-0.1.0-SNAPSHOT.jar   # UI + API em http://127.0.0.1:8080
```

Atalhos úteis:

```bash
mvn test -Dskip.npm -Dskip.installnodenpm          # só o backend, mais rápido
mvn spring-boot:run                                # backend (terminal 1)
cd frontend && npm install && npm run dev          # UI com hot reload em http://127.0.0.1:5173 (terminal 2)
cd frontend && npx vue-tsc --noEmit && npx vitest run   # tipos e testes do frontend
```

Para rodar dentro do Electron:

```bash
cd desktop && npm install && npm start
```

`mvn test -Djustinbeaver.it.network=true` também baixa o driver do SQLite do Maven Central.

## Gerar os instaladores

Em uma máquina **Linux** com JDK 21, Maven, Node e `dpkg-deb`:

```bash
packaging/build.sh            # .deb e .exe
packaging/build.sh linux      # só o .deb
packaging/build.sh windows    # só o .exe (feito no Linux também)
```

Os arquivos saem em `packaging/dist/`, junto com `SHA256SUMS`. Detalhes (Java embutido, NSIS, sandbox do Chromium):
[packaging/README.md](packaging/README.md).

### Entrar com Google (opcional)

O app funciona sem isso. Para o login e a sincronização com o Drive, crie um cliente OAuth do tipo *Desktop* no
Google Cloud, **ative a Google Drive API** no projeto e, se a tela de consentimento estiver em modo de teste, adicione
os e-mails de quem vai usar. Passe as credenciais no build:

```bash
JB_GOOGLE_CLIENT_ID=123-abc.apps.googleusercontent.com JB_GOOGLE_CLIENT_SECRET=GOCSPX-... packaging/build.sh
```

Nunca commite o `client_secret*.json` (já está no `.gitignore`). Mais em
[packaging/README.md](packaging/README.md#sign-in-with-google).

## Adicionar um banco

Coloque um YAML em `~/.justinbeaver/drivers/` (ou `src/main/resources/drivers/`):

```yaml
drivers:
  - id: mariadb-jdbc
    label: MariaDB
    className: org.mariadb.jdbc.Driver
    sampleUrl: "jdbc:mariadb://{host}[:{port}]/[{database}]"
    defaultPort: 3306
    libraries:
      - maven: "org.mariadb.jdbc:mariadb-java-client:3.5.1"   # ou `file: /caminho/driver.jar`
```

## Estrutura

| Pasta / pacote | Função |
|---|---|
| `src/main/java/io/justinbeaver/driver`, `maven`, `registry`, `provider` | Carregar drivers e registrar bancos |
| `jdbc`, `connection`, `session` | Abrir conexões, conexões salvas, sessões |
| `meta`, `query`, `edit` | Metadados, execução de SQL, dados e edição |
| `admin`, `task`, `google` | Sessões/backup, tarefas agendadas, sincronização com o Drive |
| `security` | `LocalOnlyFilter`: só loopback + cabeçalho anti-CSRF |
| `frontend/` | Vue 3 + Pinia + CodeMirror 6 |
| `desktop/` | Casca Electron (janela, tela de carregamento, backend embutido) |
| `packaging/` | Scripts do `.deb` e do `.exe` |

## Editar dados de tabela

Na aba *Data*: duplo clique (ou Enter/F2) edita a célula, `+ Row` adiciona linha, `Delete row` marca a linha.
`Save` mostra o SQL gerado antes de aplicar.

- Linhas são localizadas pela chave primária; tabelas sem chave são somente leitura.
- Valores são convertidos no servidor e enviados como parâmetros.
- Com auto-commit ligado, o salvamento é uma transação; desligado, entra na transação aberta (com savepoint).
- UPDATE/DELETE que não atinge exatamente uma linha desfaz o salvamento (a linha mudou).

## Segurança

A API executa SQL com as credenciais salvas do usuário e **não tem login**, por isso é só local: escuta em
`127.0.0.1`, rejeita `Host`/`Origin` que não sejam loopback (DNS rebinding) e exige o cabeçalho `X-JB-Client` em
requisições que alteram estado. Não exponha em rede sem adicionar autenticação de verdade.
Senhas das conexões ficam criptografadas (AES-GCM) em `connections.json`; a chave é `secret.key` (modo 600).

## Autor

Feito por **Guilherme Pereira de Moraes Borba** — guilhermepmb13@gmail.com
