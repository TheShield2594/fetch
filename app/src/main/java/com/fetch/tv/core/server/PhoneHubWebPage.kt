package com.fetch.tv.core.server

/** Pages for [PhoneHubServer]. Plain HTML and JS so they work in any phone browser. */
object PhoneHubWebPage {
    const val BACK_LINK =
        """<a href="/" style="display:block;padding:12px 16px;color:#9ab;font:15px system-ui,sans-serif;text-decoration:none">&larr; All settings</a>"""

    private val STYLE = """
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          :root { color-scheme: dark; }
          * { box-sizing: border-box; }
          body { margin: 0; background: #0f1115; color: #e8eaed; font: 16px/1.4 system-ui, -apple-system, sans-serif; }
          main { max-width: 560px; margin: 0 auto; padding: 16px; }
          h1 { font-size: 24px; margin: 8px 0 16px; }
          h2 { font-size: 18px; margin: 24px 0 8px; }
          a.card { display: block; padding: 16px; margin: 0 0 12px; border-radius: 14px; background: #1b1f27; color: inherit; text-decoration: none; }
          a.card span { display: block; color: #9aa3b2; font-size: 14px; margin-top: 4px; }
          .back { display: inline-block; margin-bottom: 8px; color: #9ab; text-decoration: none; }
          label { display: block; margin: 12px 0 4px; color: #c3c9d4; font-size: 14px; }
          input, select, textarea { width: 100%; padding: 12px; border-radius: 10px; border: 1px solid #333a46; background: #161a21; color: inherit; font: inherit; }
          button { padding: 12px 16px; border: 0; border-radius: 10px; background: #2d6cdf; color: #fff; font: inherit; cursor: pointer; }
          button.secondary { background: #2a303b; }
          button:disabled { opacity: 0.5; cursor: default; }
          .row { display: flex; gap: 8px; margin-top: 8px; }
          .row > * { flex: 1; }
          .status { min-height: 1.4em; margin-top: 8px; color: #9aa3b2; font-size: 14px; }
          .status.error { color: #ff8e8e; }
          .pad { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; max-width: 320px; margin: 16px auto; }
          .pad button { height: 72px; font-size: 22px; background: #232833; }
          .pad .ok { background: #2d6cdf; }
          .check { display: flex; align-items: center; gap: 10px; margin: 10px 0; }
          .check input { width: auto; }
          .code { font-size: 28px; letter-spacing: 4px; font-weight: 700; }
        </style>
    """.trimIndent()

    private val COMMON_JS = """
        <script>
        const TV_UNREACHABLE = 'Can’t reach the TV. Open Configure from phone on the TV, then scan the new code.';
        // A network failure (the TV closed the page) or a lapsed pairing both mean scanning again.
        async function request(path, options) {
          let response;
          try { response = await fetch(path, options); } catch (e) { throw new Error(TV_UNREACHABLE); }
          let data = {};
          try { data = await response.json(); } catch (e) {}
          if (response.status === 401 || response.status === 403) throw new Error(TV_UNREACHABLE);
          if (!response.ok) throw new Error(data.error || 'The TV couldn’t do that. Try again.');
          return data;
        }
        function post(path, body) {
          return request(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
        }
        function load(path) {
          return request(path);
        }
        function show(id, text, isError) {
          const el = document.getElementById(id);
          el.textContent = text || '';
          el.className = 'status' + (isError ? ' error' : '');
        }
        </script>
    """.trimIndent()

    private fun page(title: String, body: String, script: String = "", showBack: Boolean = true): String = """
        <!doctype html>
        <html lang="en"><head><meta charset="utf-8"><title>$title</title>
        $STYLE
        $COMMON_JS
        </head><body><main>
        ${if (showBack) "<a class=\"back\" href=\"/\">&larr; All settings</a>" else ""}
        <h1>$title</h1>
        $body
        </main>
        <script>$script</script>
        </body></html>
    """.trimIndent()

    fun landing(sections: Set<String>): String {
        val extra = buildString {
            if (PhoneHubServer.SECTION_BADGES in sections) {
                append(card("/${PhoneHubServer.SECTION_BADGES}/", "Stream badges", "Import and choose badge sets"))
            }
            if (PhoneHubServer.SECTION_POSTERS in sections) {
                append(card("/${PhoneHubServer.SECTION_POSTERS}/", "Custom posters", "Poster URL pattern"))
            }
        }
        return page(
            title = "Fetch on your TV",
            showBack = false,
            body = """
                <p style="color:#9aa3b2;font-size:14px">If this page stops responding, open Configure from phone on the TV and scan the new code.</p>
                ${card("/remote", "Remote and keyboard", "D-pad, OK, Back, play/pause, and typing into the TV")}
                ${card("/addons", "Addons", "Add a stream addon by its link. The TV asks before installing it")}
                ${card("/accounts", "Accounts", "MDBList, Trakt, Simkl and media servers")}
                ${card("/ranking", "Stream ranking", "Resolution, size, codecs and keywords for Best match")}
                ${card("/profiles", "Move a profile", "Save a profile to a file, or add one from another TV")}
                $extra
                <h2>On the TV</h2>
                <p style="color:#9aa3b2;font-size:14px">Removing and reordering addons, catalog order and collections change what the TV loads, so they have screens of their own. Open them on the TV and use their own phone code.</p>
            """
        )
    }

    private fun card(href: String, title: String, subtitle: String) =
        """<a class="card" href="$href">$title<span>$subtitle</span></a>"""

    fun remote(): String = page(
        title = "Remote",
        body = """
            <div class="pad">
              <span></span><button data-key="UP" aria-label="Up">&#9650;</button><span></span>
              <button data-key="LEFT" aria-label="Left">&#9664;</button><button class="ok" data-key="OK">OK</button><button data-key="RIGHT" aria-label="Right">&#9654;</button>
              <button data-key="BACK" aria-label="Back">&#8617;</button><button data-key="DOWN" aria-label="Down">&#9660;</button><button data-key="PLAY_PAUSE" aria-label="Play or pause">&#9199;</button>
            </div>
            <div class="row"><button class="secondary" data-key="REWIND">&#9194; Rewind</button><button class="secondary" data-key="FAST_FORWARD">Forward &#9193;</button></div>
            <h2>Keyboard</h2>
            <label for="text">Type here, then send it to the text field selected on the TV</label>
            <input id="text" autocomplete="off" autocapitalize="off" spellcheck="false" maxlength="500">
            <div class="row">
              <button id="send">Type on TV</button>
              <button class="secondary" data-key="DELETE">&#9003; Delete</button>
            </div>
            <div class="row">
              <button class="secondary" id="search">Search on TV</button>
              <button class="secondary" id="settingsSearch">Search settings</button>
            </div>
            <div id="status" class="status"></div>
        """,
        script = """
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
              const done = type === 'search' ? 'Searching on the TV' : type === 'settings_search' ? 'Searching settings on the TV' : 'Sent';
              post('/api/remote', body).then(function () { field.value = ''; show('status', done); })
                .catch(function (e) { show('status', e.message, true); });
            }
            document.getElementById('send').addEventListener('click', function () { sendText('text'); });
            document.getElementById('search').addEventListener('click', function () { sendText('search'); });
            document.getElementById('settingsSearch').addEventListener('click', function () { sendText('settings_search'); });
            document.getElementById('text').addEventListener('keydown', function (e) { if (e.key === 'Enter') sendText('text'); });
        """
    )

    fun accounts(): String = page(
        title = "Accounts",
        body = """
            <div id="loadStatus" class="status"></div>
            <h2>MDBList ratings</h2>
            <label for="mdbKey">API key</label>
            <input id="mdbKey" type="password" autocomplete="off">
            <div class="row"><button id="saveMdb">Save key</button></div>
            <div id="mdbStatus" class="status"></div>

            <h2>Trakt and Simkl</h2>
            <div class="row"><button id="trakt">Sign in to Trakt</button><button id="simkl">Sign in to Simkl</button></div>
            <div id="trackerCode"></div>
            <div id="trackerStatus" class="status"></div>

            <h2>Media server</h2>
            <label for="serverProvider">Server</label>
            <select id="serverProvider"></select>
            <label for="serverAddress">Address</label>
            <input id="serverAddress" placeholder="http://192.168.1.10:8096" autocomplete="off" autocapitalize="off">
            <label for="serverUser">Username</label>
            <input id="serverUser" autocomplete="off" autocapitalize="off">
            <label for="serverPassword">Password</label>
            <input id="serverPassword" type="password" autocomplete="off">
            <div class="row"><button id="connectServer">Connect</button></div>
            <div id="serverStatus" class="status"></div>
        """,
        script = """
            function escapeHtml(value) {
              return String(value).replace(/[&<>"']/g, function (c) { return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]; });
            }
            function refresh() {
              load('/api/accounts').then(function (s) {
                show('loadStatus', '');
                show('mdbStatus', s.mdbList ? 'A key is saved.' : '');
                const trackers = [];
                if (s.trakt.connected) trackers.push('Trakt' + (s.trakt.username ? ' (' + s.trakt.username + ')' : ''));
                if (s.simkl.connected) trackers.push('Simkl' + (s.simkl.username ? ' (' + s.simkl.username + ')' : ''));
                show('trackerStatus', trackers.length ? 'Connected: ' + trackers.join(', ') : '');
                const select = document.getElementById('serverProvider');
                if (!select.options.length) {
                  s.mediaServerProviders.forEach(function (p) {
                    const option = document.createElement('option');
                    option.value = p.id; option.textContent = p.name; select.appendChild(option);
                  });
                }
                if (s.mediaServers.length) show('serverStatus', 'Connected: ' + s.mediaServers.join(', '));
              }).catch(function (e) {
                show('loadStatus', e.message === TV_UNREACHABLE ? e.message : 'Couldn’t load your accounts. Reload this page.', true);
              });
            }
            document.getElementById('saveMdb').addEventListener('click', function () {
              show('mdbStatus', 'Checking...');
              post('/api/accounts/mdblist', { apiKey: document.getElementById('mdbKey').value })
                .then(function (d) { document.getElementById('mdbKey').value = ''; show('mdbStatus', d.message || 'Saved'); refresh(); })
                .catch(function (e) { show('mdbStatus', e.message, true); });
            });
            function startTracker(tracker) {
              show('trackerStatus', 'Starting...');
              post('/api/accounts/tracker', { tracker: tracker }).then(function (d) {
                document.getElementById('trackerCode').innerHTML =
                  '<p>Open <a href="' + escapeHtml(d.verificationUrl) + '" target="_blank" rel="noopener" style="color:#8ab4ff">' + escapeHtml(d.verificationUrl) + '</a> and enter:</p>' +
                  '<p class="code">' + escapeHtml(d.userCode) + '</p>';
                show('trackerStatus', 'Waiting for approval. The TV finishes signing in by itself.');
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
              show('serverStatus', 'Connecting...');
              post('/api/accounts/mediaserver', {
                provider: document.getElementById('serverProvider').value,
                address: document.getElementById('serverAddress').value,
                username: document.getElementById('serverUser').value,
                password: document.getElementById('serverPassword').value
              }).then(function (d) { document.getElementById('serverPassword').value = ''; show('serverStatus', d.message || 'Connected'); refresh(); })
                .catch(function (e) { show('serverStatus', e.message, true); });
            });
            refresh();
        """
    )

    fun ranking(): String = page(
        title = "Stream ranking",
        body = """
            <p style="color:#9aa3b2;font-size:14px">Used by Best match to pick a stream. Changes apply to the profile that is active on the TV.</p>
            <label for="preferredResolution">Preferred resolution</label><select id="preferredResolution"></select>
            <label for="maxResolution">Highest resolution</label><select id="maxResolution"></select>
            <label for="minResolution">Lowest resolution</label><select id="minResolution"></select>
            <label for="maxSizeGb">Largest file (GB, empty for no limit)</label><input id="maxSizeGb" type="number" min="0" step="0.5">
            <label for="preferredMaxSizeGb">Prefer files up to (GB)</label><input id="preferredMaxSizeGb" type="number" min="0" step="0.5">
            <div class="check"><input type="checkbox" id="preferHdr"><label for="preferHdr">Prefer HDR</label></div>
            <div class="check"><input type="checkbox" id="excludeHdr"><label for="excludeHdr">Never pick HDR</label></div>
            <div class="check"><input type="checkbox" id="excludeDolbyVision"><label for="excludeDolbyVision">Never pick Dolby Vision</label></div>
            <div class="check"><input type="checkbox" id="excludeCamAndTelesync"><label for="excludeCamAndTelesync">Skip CAM and telesync</label></div>
            <div class="check"><input type="checkbox" id="exclude3d"><label for="exclude3d">Skip 3D</label></div>
            <div class="check"><input type="checkbox" id="preferCachedDebrid"><label for="preferCachedDebrid">Prefer cached debrid results</label></div>
            <div class="check"><input type="checkbox" id="preferOwnLibrary"><label for="preferOwnLibrary">Prefer my media servers</label></div>
            <div class="check"><input type="checkbox" id="dropUndecodable"><label for="dropUndecodable">Skip formats this TV can't play</label></div>
            <label for="preferredKeywords">Preferred keywords (one per line)</label><textarea id="preferredKeywords" rows="3"></textarea>
            <label for="excludedKeywords">Excluded keywords (one per line)</label><textarea id="excludedKeywords" rows="3"></textarea>
            <label for="minSizeGb">Smallest file (GB, empty for no minimum)</label><input id="minSizeGb" type="number" min="0" step="0.5">
            <label>Preferred audio languages (first ticked ranks highest)</label><div class="checks" id="preferredLanguages"></div>
            <label>Only languages (empty for any)</label><div class="checks" id="requiredLanguages"></div>
            <label>Skip languages</label><div class="checks" id="excludedLanguages"></div>
            <label>Skip resolutions</label><div class="checks" id="excludedResolutions"></div>
            <label>Skip release types</label><div class="checks" id="excludedSources"></div>
            <label>Skip video codecs</label><div class="checks" id="excludedCodecs"></div>
            <label>Skip audio formats (when a release has nothing else)</label><div class="checks" id="excludedAudio"></div>
            <label for="preferredReleaseGroups">Preferred release groups (one per line)</label><textarea id="preferredReleaseGroups" rows="3"></textarea>
            <label for="excludedReleaseGroups">Skipped release groups (one per line)</label><textarea id="excludedReleaseGroups" rows="3"></textarea>
            <label for="preferredAddons">Preferred addons (one per line)</label><textarea id="preferredAddons" rows="3"></textarea>
            <label for="maxResults">Results to keep (0 for all)</label><input id="maxResults" type="number" min="0" step="1">
            <label for="maxPerResolution">Results per resolution (0 for all)</label><input id="maxPerResolution" type="number" min="0" step="1">
            <label for="maxPerSource">Results per release type (0 for all)</label><input id="maxPerSource" type="number" min="0" step="1">
            <div class="row"><button id="save" disabled>Save</button></div>
            <div id="status" class="status"></div>
        """,
        script = """
            const RESOLUTIONS = ${resolutionOptionsJs()};
            const BOOLEANS = ['preferHdr','excludeHdr','excludeDolbyVision','excludeCamAndTelesync','exclude3d','preferCachedDebrid','preferOwnLibrary','dropUndecodable'];
            const CHOICES = {
              preferredLanguages: ${enumOptionsJs(com.fetch.tv.core.streams.ranking.StreamLanguage.entries) { it.label }},
              requiredLanguages: ${enumOptionsJs(com.fetch.tv.core.streams.ranking.StreamLanguage.entries) { it.label }},
              excludedLanguages: ${enumOptionsJs(com.fetch.tv.core.streams.ranking.StreamLanguage.entries) { it.label }},
              excludedResolutions: ${enumOptionsJs(com.fetch.tv.core.streams.ranking.StreamResolution.entries.filter { it.label.isNotBlank() }) { it.label }},
              excludedSources: ${enumOptionsJs(com.fetch.tv.core.streams.ranking.ReleaseSource.entries.filter { it.label.isNotBlank() }) { it.label }},
              excludedCodecs: ${enumOptionsJs(com.fetch.tv.core.streams.ranking.VideoCodec.entries.filter { it.label.isNotBlank() }) { it.label }},
              excludedAudio: ${enumOptionsJs(com.fetch.tv.core.streams.ranking.AudioFormat.entries) { it.label }}
            };
            const NUMBERS = ['maxResults','maxPerResolution','maxPerSource'];
            Object.keys(CHOICES).forEach(function (id) {
              const box = document.getElementById(id);
              CHOICES[id].forEach(function (c) {
                const label = document.createElement('label');
                label.className = 'check';
                const input = document.createElement('input');
                input.type = 'checkbox'; input.value = c[0]; input.dataset.group = id;
                label.appendChild(input); label.appendChild(document.createTextNode(' ' + c[1]));
                box.appendChild(label);
              });
            });
            // Ticked order is kept, so the first language ticked is the most preferred.
            const ticked = {};
            document.addEventListener('change', function (e) {
              const g = e.target.dataset && e.target.dataset.group;
              if (!g) return;
              ticked[g] = (ticked[g] || []).filter(function (v) { return v !== e.target.value; });
              if (e.target.checked) ticked[g].push(e.target.value);
            });
            function setChoices(id, values) {
              ticked[id] = (values || []).slice();
              document.querySelectorAll('[data-group="' + id + '"]').forEach(function (i) { i.checked = ticked[id].indexOf(i.value) >= 0; });
            }
            // Save stays off until the TV's rules have loaded, so a failed load can't save blank rules over them.
            let current = null;
            ['preferredResolution','maxResolution','minResolution'].forEach(function (id) {
              const select = document.getElementById(id);
              RESOLUTIONS.forEach(function (r) { const o = document.createElement('option'); o.value = r[0]; o.textContent = r[1]; select.appendChild(o); });
            });
            function lines(id) { return document.getElementById(id).value.split('\n').map(function (s) { return s.trim(); }).filter(Boolean); }
            function number(id) { const v = document.getElementById(id).value; return v === '' ? null : Number(v); }
            load('/api/ranking').then(function (d) {
              if (!d.rules) throw new Error('No rules');
              const loaded = d.rules;
              ['preferredResolution','maxResolution','minResolution'].forEach(function (id) { document.getElementById(id).value = loaded[id]; });
              document.getElementById('maxSizeGb').value = loaded.maxSizeGb == null ? '' : loaded.maxSizeGb;
              document.getElementById('preferredMaxSizeGb').value = loaded.preferredMaxSizeGb == null ? '' : loaded.preferredMaxSizeGb;
              BOOLEANS.forEach(function (id) { document.getElementById(id).checked = !!loaded[id]; });
              document.getElementById('preferredKeywords').value = (loaded.preferredKeywords || []).join('\n');
              document.getElementById('excludedKeywords').value = (loaded.excludedKeywords || []).join('\n');
              document.getElementById('minSizeGb').value = loaded.minSizeGb == null ? '' : loaded.minSizeGb;
              Object.keys(CHOICES).forEach(function (id) { setChoices(id, loaded[id]); });
              document.getElementById('preferredReleaseGroups').value = (loaded.preferredReleaseGroups || []).join('\n');
              document.getElementById('excludedReleaseGroups').value = (loaded.excludedReleaseGroups || []).join('\n');
              document.getElementById('preferredAddons').value = (loaded.preferredAddons || []).join('\n');
              NUMBERS.forEach(function (id) { document.getElementById(id).value = loaded[id] || 0; });
              current = loaded;
              document.getElementById('save').disabled = false;
            }).catch(function (e) {
              show('status', e.message === TV_UNREACHABLE ? e.message : 'Couldn’t load the current rules, so they can’t be saved. Reload this page.', true);
            });
            document.getElementById('save').addEventListener('click', function () {
              if (!current) return;
              const rules = Object.assign({}, current);
              ['preferredResolution','maxResolution','minResolution'].forEach(function (id) { rules[id] = document.getElementById(id).value; });
              rules.maxSizeGb = number('maxSizeGb');
              rules.preferredMaxSizeGb = number('preferredMaxSizeGb');
              BOOLEANS.forEach(function (id) { rules[id] = document.getElementById(id).checked; });
              rules.preferredKeywords = lines('preferredKeywords');
              rules.excludedKeywords = lines('excludedKeywords');
              rules.minSizeGb = number('minSizeGb');
              Object.keys(CHOICES).forEach(function (id) { rules[id] = ticked[id] || []; });
              rules.preferredReleaseGroups = lines('preferredReleaseGroups');
              rules.excludedReleaseGroups = lines('excludedReleaseGroups');
              rules.preferredAddons = lines('preferredAddons');
              NUMBERS.forEach(function (id) { rules[id] = Math.max(0, parseInt(document.getElementById(id).value || '0', 10) || 0); });
              post('/api/ranking', { rules: rules }).then(function () { current = rules; show('status', 'Saved'); })
                .catch(function (e) { show('status', e.message, true); });
            });
        """
    )

    fun profiles(): String = page(
        title = "Move a profile",
        body = """
            <p style="color:#9aa3b2;font-size:14px">A profile file holds the profile's name and avatar, its settings, its library, and what it has watched. Sign-ins, API keys, PINs and parental limits stay on this TV.</p>
            <h2>Save from this TV</h2>
            <div id="profiles"></div>
            <div id="exportStatus" class="status"></div>
            <h2>Add to this TV</h2>
            <label for="file">A profile file saved from another TV</label>
            <input id="file" type="file" accept=".json,application/json">
            <div class="row"><button id="import">Add profile</button></div>
            <div id="importStatus" class="status"></div>
        """,
        script = """
            function escapeHtml(value) {
              return String(value).replace(/[&<>"']/g, function (c) { return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]; });
            }
            function refresh() {
              load('/api/profiles').then(function (d) {
                const box = document.getElementById('profiles');
                box.innerHTML = d.profiles.map(function (p) {
                  return '<a class="card" download href="/api/profiles/export?id=' + encodeURIComponent(p.id) + '">' +
                    escapeHtml(p.name) + '<span>Save as a file</span></a>';
                }).join('');
                show('exportStatus', d.profiles.length ? '' : 'No profile can be saved. A profile with a PIN has to have it removed on the TV first.');
              }).catch(function (e) {
                show('exportStatus', e.message === TV_UNREACHABLE ? e.message : 'Couldn’t load this TV’s profiles. Reload this page.', true);
              });
            }
            document.getElementById('import').addEventListener('click', function () {
              const file = document.getElementById('file').files[0];
              if (!file) { show('importStatus', 'Choose a profile file first.', true); return; }
              show('importStatus', 'Adding...');
              file.text().then(function (text) {
                let document_;
                try { document_ = JSON.parse(text); } catch (e) { throw new Error("That file isn't a Fetch profile."); }
                return post('/api/profiles/import', document_);
              }).then(function (d) { show('importStatus', d.message || 'Added'); refresh(); })
                .catch(function (e) { show('importStatus', e.message, true); });
            });
            refresh();
        """
    )

    fun addons(): String = page(
        title = "Addons",
        body = """
            <p style="color:#9aa3b2;font-size:14px">Streams come from addons. Paste an addon's link (it usually ends in manifest.json) and the TV shows what it is and asks before installing it. Debrid services such as Real-Debrid, Premiumize and TorBox are signed in on the addon's own setup page.</p>
            <label for="url">Addon link</label>
            <input id="url" type="url" inputmode="url" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="https://example.com/manifest.json">
            <div class="row"><button id="send">Send to TV</button></div>
            <div id="status" class="status"></div>
            <div id="configure"></div>
            <div id="suggestionsBlock" hidden>
              <h2>Suggestions</h2>
              <div id="suggestions"></div>
            </div>
            <h2>On this TV</h2>
            <div id="installed" class="status"></div>
        """,
        script = """
            function escapeHtml(value) {
              return String(value).replace(/[&<>"']/g, function (c) { return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]; });
            }
            function safeLink(url) {
              return /^https?:\/\//i.test(url) ? url : '#';
            }
            function refresh() {
              load('/api/addons').then(function (d) {
                const streams = d.installed.filter(function (a) { return a.streams; }).length;
                document.getElementById('installed').textContent = d.installed.length
                  ? d.installed.map(function (a) { return a.name; }).join(', ') + '. ' +
                    (streams ? streams + (streams === 1 ? ' of them gives' : ' of them give') + ' streams.' : 'None of them gives streams yet.')
                  : 'No addons yet.';
                const box = document.getElementById('suggestions');
                document.getElementById('suggestionsBlock').hidden = !d.suggestions.length;
                box.innerHTML = d.suggestions.map(function (s, i) {
                  return '<a class="card" href="#" data-index="' + i + '">' + escapeHtml(s.name) + '<span>' + escapeHtml(s.description) + '</span></a>';
                }).join('');
                box.querySelectorAll('[data-index]').forEach(function (el) {
                  el.addEventListener('click', function (e) {
                    e.preventDefault();
                    const s = d.suggestions[Number(el.dataset.index)];
                    document.getElementById('url').value = s.url;
                    send();
                  });
                });
              }).catch(function (e) {
                show('installed', e.message === TV_UNREACHABLE ? e.message : 'Couldn’t load this TV’s addons. Reload this page.', true);
              });
            }
            let pollTimer = null;
            function showConfigure(d, required) {
              const box = document.getElementById('configure');
              if (!d.configureUrl) { box.innerHTML = ''; return; }
              const name = escapeHtml(d.name || 'This addon');
              box.innerHTML = '<p style="color:#9aa3b2;font-size:14px">' + (required
                ? name + ' has to be set up before it can be installed. Open its setup page, finish there, then paste the link it gives you above and send it again.'
                : name + ' can also be set up first, for example to sign in to a debrid service. Open its setup page, then send the link it gives you instead.') + '</p>' +
                '<a class="card" target="_blank" rel="noopener" href="' + escapeHtml(safeLink(d.configureUrl)) + '">Open setup page<span>' + escapeHtml(d.configureUrl) + '</span></a>';
            }
            function handle(d) {
              clearTimeout(pollTimer);
              switch (d.status) {
                case 'pending':
                  show('status', 'Confirm ' + (d.name || 'the addon') + ' on the TV.');
                  showConfigure(d, false);
                  pollTimer = setTimeout(function () {
                    fetch('/api/addons/status?id=' + encodeURIComponent(d.id)).then(function (r) { return r.json(); }).then(handle)
                      .catch(function () { handle(d); });
                  }, 1500);
                  break;
                case 'installed':
                  show('status', d.message || ((d.name || 'The addon') + ' is installed.'));
                  document.getElementById('url').value = '';
                  document.getElementById('configure').innerHTML = '';
                  refresh();
                  break;
                case 'rejected':
                  show('status', 'Declined on the TV.', true);
                  break;
                case 'needs_configuration':
                  show('status', d.message || 'This addon needs setting up first.', true);
                  showConfigure(d, true);
                  break;
                default:
                  show('status', d.message || 'Couldn\'t add the addon.', true);
              }
            }
            function send() {
              const url = document.getElementById('url').value.trim();
              if (!url) { show('status', 'Paste an addon link first.', true); return; }
              show('status', 'Reading the addon...');
              document.getElementById('configure').innerHTML = '';
              post('/api/addons', { url: url }).then(handle).catch(function (e) { show('status', e.message, true); });
            }
            document.getElementById('send').addEventListener('click', send);
            document.getElementById('url').addEventListener('keydown', function (e) { if (e.key === 'Enter') send(); });
            refresh();
        """
    )

    private fun <T : Enum<T>> enumOptionsJs(values: List<T>, label: (T) -> String): String =
        values.joinToString(prefix = "[", postfix = "]") { value ->
            "['${value.name}','${label(value).replace("'", "\\'")}']"
        }

    private fun resolutionOptionsJs(): String =
        com.fetch.tv.core.streams.ranking.StreamResolution.entries.joinToString(prefix = "[", postfix = "]") { resolution ->
            "['${resolution.name}','${resolution.label.ifBlank { "Any" }}']"
        }
}
