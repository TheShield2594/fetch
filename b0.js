
            document.querySelectorAll('[data-key]').forEach(function (button) {
              button.addEventListener('click', function () {
                post('/api/remote', { type: 'key', key: button.dataset.key }).then(function () { show('status', ''); })
                  .catch(function (e) { show('status', e.message, true); });
              });
            });
            function sendText(type) {
              const field = document.getElementById('text');
              const value = field.value;
              if (!value) return;
              const body = type === 'text' ? { type: 'text', text: value } : { type: type, query: value };
              const done = type === 'search' ? "Searching on the TV"
                : type === 'settings_search' ? "Searching settings on the TV" : "Sent";
              post('/api/remote', body).then(function () { field.value = ''; show('status', done); })
                .catch(function (e) { show('status', e.message, true); });
            }
            document.getElementById('send').addEventListener('click', function () { sendText('text'); });
            document.getElementById('search').addEventListener('click', function () { sendText('search'); });
            document.getElementById('settingsSearch').addEventListener('click', function () { sendText('settings_search'); });
            document.getElementById('text').addEventListener('keydown', function (e) { if (e.key === 'Enter') sendText('text'); });
        