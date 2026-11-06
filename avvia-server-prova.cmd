@echo off
rem Avvia il server Top Cash in una finestra (senza installarlo come servizio), solo su questo PC.
rem Chiudi la finestra per fermarlo. Per l'uso in ufficio usa installa-server.ps1.
title Top Cash Studio Server
"%~dp0server-app\TopCash.Server.exe" --data="%~dp0data" --urls=http://127.0.0.1:8470
pause
