**Most gaming hostings run on the Pterodactyl panel.**  
Everyone who buys a server gets a dedicated Docker container where they can run their Minecraft server.  

But there is a limitation that nobody talks about or considers normal.  
You can run only **one** server inside a single such container.   
This means you need at least 2-3 Docker containers if you want to host a full setup with Velocity, a lobby, and other modes.  

My plugin removes this necessity.   
**Supervisor** allows you to run child servers inside **one** Docker container.  

---

### Commands

All commands are executed in the Velocity console or in the game chat, if the player has the appropriate permissions.  

`/supervisor list` - Shows the list of servers and their current state.  
`/supervisor status <server>` - Shows detailed server status.  
`/supervisor start <server>` - Starts the specified child server.  
`/supervisor stop <server>` - Sends the stop command to the server. If it does not shut down within 30 seconds, it will be forcibly stopped.  
`/supervisor restart <server>` - Restarts the server.    
`/supervisor reload` - Reloads the `config.ini` file without restarting the proxy.  
`/supervisor cmd <server> <command>` - Sends any command directly to the child server console.    
`/supervisor` or `/supervisor help` - Shows a list of all available commands.  

---

### Permissions  
Each command has its own permission.  
If a player has `supervisor.admin`, they are allowed to do everything.  
 
`supervisor.list` - access to `/supervisor list`  
`supervisor.status` - access to `/supervisor status`  
`supervisor.start` - access to `/supervisor start`  
`supervisor.stop` - access to `/supervisor stop`  
`supervisor.restart` - access to `/supervisor restart`  
`supervisor.reload` - access to `/supervisor reload`  
`supervisor.console` - access to `/supervisor cmd`  
`supervisor.use` - all permissions except `cmd`  
`supervisor.admin` - full access (including `supervisor.use` and `supervisor.console`)  

---

<details>
<summary> Русская версия (Russian version) </summary>

**Большинство игровых хостингов работают на панели Pterodactyl.**  
Каждый, кто покупает сервер, получает выделенный Docker-контейнер,  
в котором может запустить свой Minecraft-сервер.  

Но есть ограничение, о котором никто не говорит или считает это нормой.  
Вы можете запустить всего **один** сервер внутри одного такого контейнера.   
То есть вам нужно минимум 2-3 Docker-контейнера, если вы хотите разместить полноценную связку Velocity, с лобби и другими режимами.  

Мой плагин лишает вас этой необходимости.   
**Supervisor** позволяет запускать дочерние серверы внутри **одного** Docker-контейнера.  

---

### Команды

Все команды выполняются в консоли Velocity или в чате игры, если игроку выданы соответствующие права.  

`/supervisor list` - Показывает список серверов и их текущее состояние.  
`/supervisor status <server>` - Показывает подробный статус сервера.  
`/supervisor start <server>` - Запускает указанный дочерний сервер.  
`/supervisor stop <server>` - Отправляет серверу команду stop. Если он не выключится за 30 секунд, он будет принудительно остановлен.  
`/supervisor restart <server>` - Перезапускает сервер.    
`/supervisor reload` - Перечитывает файл `config.ini` без перезапуска прокси.  
`/supervisor cmd <server> <команда>` - Отправляет любую команду напрямую в консоль дочернего сервера.    
`/supervisor` или `/supervisor help` - Показывает список всех доступных команд.  

---

### Права доступа  
Для каждой команды есть свое право.  
Если у игрока есть `supervisor.admin`, ему разрешено всё.  
 
`supervisor.list` - доступ к `/supervisor list`  
`supervisor.status` - доступ к `/supervisor status`  
`supervisor.start` - доступ к `/supervisor start`  
`supervisor.stop` - доступ к `/supervisor stop`  
`supervisor.restart` - доступ к `/supervisor restart`  
`supervisor.reload` - доступ к `/supervisor reload`  
`supervisor.console` - доступ к `/supervisor cmd`  
`supervisor.use` - все права, кроме `cmd`  
`supervisor.admin` - полный доступ (включая `supervisor.use` и `supervisor.console`)  

</details>
