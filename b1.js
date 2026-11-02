
            const SEPARATOR = ", ";
            const CONNECTED_LIST = "Connected: {0}";
            function refresh() {
              load('/api/accounts').then(function (s) {
                show('loadStatus', '');
                show('mdbStatus', s.mdbList ? "A key is saved." : '');
                const trackers = [];
                if (s.trakt.connected) trackers.push('Trakt' + (s.trakt.username ? ' (' + s.trakt.username + ')' : ''));
                if (s.simkl.connected) trackers.push('Simkl' + (s.simkl.username ? ' (' + s.simkl.username + ')' : ''));
                show('trackerStatus', trackers.length ? fmt(CONNECTED_LIST, trackers.join(SEPARATOR)) : '');
                const select = document.getElementById('serverProvider');
                if (!select.options.length) {
                  s.mediaServerProviders.forEach(function (p) {
                    const option = document.createElement('option');
                    option.value = p.id; option.textContent = p.name; select.appendChild(option);
                  });
                }
                if (s.mediaServers.length) show('serverStatus', fmt(CONNECTED_LIST, s.mediaServers.join(SEPARATOR)));
              }).catch(function (e) {
                show('loadStatus', e.message === TV_UNREACHABLE ? e.message : "Couldn’t load your accounts. Reload this page.", true);
              });
            }
            document.getElementById('saveMdb').addEventListener('click', function () {
              show('mdbStatus', "Checking…");
              post('/api/accounts/mdblist', { apiKey: document.getElementById('mdbKey').value })
                .then(function (d) { document.getElementById('mdbKey').value = ''; show('mdbStatus', d.message || "Saved"); refresh(); })
                .catch(function (e) { show('mdbStatus', e.message, true); });
            });
            function startTracker(tracker) {
              show('trackerStatus', "Starting…");
              post('/api/accounts/tracker', { tracker: tracker }).then(function (d) {
                const link = '<a href="' + escapeHtml(d.verificationUrl) + '" target="_blank" rel="noopener" style="color:#8ab4ff">' + escapeHtml(d.verificationUrl) + '</a>';
                document.getElementById('trackerCode').innerHTML =
                  '<p>' + fmt(escapeHtml("Open {0} and enter:"), link) + '</p>' +
                  '<p class="code">' + escapeHtml(d.userCode) + '</p>';
                show('trackerStatus', "Waiting for approval. The TV finishes signing in by itself.");
                poll();
              }).catch(function (e) { show('trackerStatus', e.message, true); });
            }
            let pollTimer = null;
            function poll() {
              clearInterval(pollTimer);
              let tries = 0;
              pollTimer = setInterval(function () { refresh(); if (++tries > 120) clearInterval(pollTimer); }, 5000);
            }
            document.getElementById('trakt').addEventListener('click', function () { startTracker('trakt'); });
            document.getElementById('simkl').addEventListener('click', function () { startTracker('simkl'); });
            document.getElementById('connectServer').addEventListener('click', function () {
              show('serverStatus', "Connecting…");
              post('/api/accounts/mediaserver', {
                provider: document.getElementById('serverProvider').value,
                address: document.getElementById('serverAddress').value,
                username: document.getElementById('serverUser').value,
                password: document.getElementById('serverPassword').value
              }).then(function (d) { document.getElementById('serverPassword').value = ''; show('serverStatus', d.message || "Connected"); refresh(); })
                .catch(function (e) { show('serverStatus', e.message, true); });
            });
            refresh();
        