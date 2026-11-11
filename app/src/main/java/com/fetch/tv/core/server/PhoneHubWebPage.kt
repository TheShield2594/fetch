package com.fetch.tv.core.server

import android.content.res.Resources
import androidx.annotation.StringRes
import com.fetch.tv.R
import com.fetch.tv.core.streams.ranking.AudioFormat
import com.fetch.tv.core.streams.ranking.ReleaseSource
import com.fetch.tv.core.streams.ranking.StreamLanguage
import com.fetch.tv.core.streams.ranking.StreamResolution
import com.fetch.tv.core.streams.ranking.VideoCodec
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pages for [PhoneHubServer]. Plain HTML and JS so they work in any phone browser. Every string
 * comes from [resources], so the pages follow the TV's language.
 */
class PhoneHubWebPage(private val resources: Resources) {

    /** A string for HTML text or an attribute value. */
    private fun h(@StringRes id: Int): String = escapeHtml(resources.getString(id))

    /**
     * A string as a JS string literal. A `%1$s` placeholder becomes `{0}`, which the pages fill
     * with `fmt()`, so translators get whole sentences.
     */
    private fun j(@StringRes id: Int): String = jsString(resources.getString(id).replace("%1\$s", "{0}"))

    // From the strings themselves, not the TV's locale: an untranslated page falls back to
    // English and must say so, or screen readers read it with the wrong language's rules.
    private val languageTag: String = resources.getString(R.string.phone_web_lang).ifBlank { "en" }

    private val style = """
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          :root { color-scheme: dark; }
          * { box-sizing: border-box; }
          [hidden] { display: none !important; }
          body { margin: 0; background: #0f1115; color: #e8eaed; font: 16px/1.4 system-ui, -apple-system, sans-serif; }
          main { max-width: 560px; margin: 0 auto; padding: 16px; }
          h1 { font-size: 24px; margin: 8px 0 16px; }
          h2 { font-size: 18px; margin: 24px 0 8px; }
          .card { display: block; width: 100%; padding: 16px; margin: 0 0 12px; border: 0; border-radius: 14px; background: #1b1f27; color: inherit; font: inherit; text-align: start; text-decoration: none; cursor: pointer; }
          .card span { display: block; color: #9aa3b2; font-size: 14px; margin-top: 4px; }
          .back { display: inline-block; padding: 12px 0; margin-bottom: 4px; color: #9ab; text-decoration: none; }
          .hint { color: #9aa3b2; font-size: 14px; }
          label, legend { display: block; margin: 12px 0 4px; padding: 0; color: #c3c9d4; font-size: 14px; }
          fieldset { border: 0; margin: 0; padding: 0; min-width: 0; }
          input, select, textarea { width: 100%; padding: 12px; border-radius: 10px; border: 1px solid #646d7e; background: #161a21; color: inherit; font: inherit; }
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
          .check { display: flex; align-items: center; gap: 10px; min-height: 44px; margin: 0; }
          .check > label { flex: 1; align-self: stretch; display: flex; align-items: center; margin: 0; }
          .check input { width: 20px; height: 20px; flex: none; margin: 0; }
          .code { font-size: 28px; letter-spacing: 4px; font-weight: 700; }
        </style>
    """.trimIndent()

    private val commonJs = """
        <script>
        const TV_UNREACHABLE = ${j(R.string.phone_web_tv_unreachable)};
        const TV_FAILED = ${j(R.string.phone_web_tv_failed)};
        // A network failure (the TV closed the page) or a lapsed pairing both mean scanning again.
        async function request(path, options) {
          let response;
          try { response = await fetch(path, options); } catch (e) { throw new Error(TV_UNREACHABLE); }
          let data = {};
          try { data = await response.json(); } catch (e) {}
          if (response.status === 401 || response.status === 403) throw new Error(TV_UNREACHABLE);
          if (!response.ok) throw new Error(data.error || TV_FAILED);
          return data;
        }
        function post(path, body) {
          return request(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
        }
        function load(path) {
          return request(path);
        }
        // Errors are announced at once; everything else waits for the screen reader to finish.
        function show(id, text, isError) {
          const el = document.getElementById(id);
          el.setAttribute('role', isError ? 'alert' : 'status');
          el.textContent = text || '';
          el.className = 'status' + (isError ? ' error' : '');
        }
        function fmt(template, value) {
          return template.split('{0}').join(value);
        }
        function escapeHtml(value) {
          return String(value).replace(/[&<>"']/g, function (c) { return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]; });
        }
        </script>
    """.trimIndent()

    private fun page(title: String, body: String, script: String = "", showBack: Boolean = true): String = """
        <!doctype html>
        <html lang="$languageTag"><head><meta charset="utf-8"><title>$title</title>
        $style
        $commonJs
        </head><body><main>
        ${if (showBack) "<a class=\"back\" href=\"/\">&larr; ${h(R.string.phone_web_back)}</a>" else ""}
        <h1>$title</h1>
        $body
        </main>
        <script>$script</script>
        </body></html>
    """.trimIndent()

    fun landing(sections: Set<String>): String {
        val extra = buildString {
            if (PhoneHubServer.SECTION_BADGES in sections) {
                append(card("/${PhoneHubServer.SECTION_BADGES}/", R.string.phone_web_card_badges, R.string.phone_web_card_badges_sub))
            }
            if (PhoneHubServer.SECTION_POSTERS in sections) {
                append(card("/${PhoneHubServer.SECTION_POSTERS}/", R.string.phone_web_card_posters, R.string.phone_web_card_posters_sub))
            }
        }
        return page(
            title = h(R.string.phone_web_landing_title),
            showBack = false,
            body = """
                <p class="hint">${h(R.string.phone_web_landing_hint)}</p>
                ${card("/remote", R.string.phone_web_card_remote, R.string.phone_web_card_remote_sub)}
                ${card("/addons", R.string.phone_web_card_addons, R.string.phone_web_card_addons_sub)}
                ${card("/accounts", R.string.phone_web_card_accounts, R.string.phone_web_card_accounts_sub)}
                ${card("/website", R.string.phone_web_card_website, R.string.phone_web_card_website_sub)}
                ${card("/ranking", R.string.phone_web_card_ranking, R.string.phone_web_card_ranking_sub)}
                ${card("/profiles", R.string.phone_web_card_profiles, R.string.phone_web_card_profiles_sub)}
                ${card("/backup", R.string.phone_web_card_backup, R.string.phone_web_card_backup_sub)}
                $extra
                <h2>${h(R.string.phone_web_on_tv_heading)}</h2>
                <p class="hint">${h(R.string.phone_web_on_tv_body)}</p>
            """
        )
    }

    private fun card(href: String, @StringRes title: Int, @StringRes subtitle: Int) =
        """<a class="card" href="$href">${h(title)}<span>${h(subtitle)}</span></a>"""

    fun remote(): String = page(
        title = h(R.string.phone_web_remote_title),
        body = """
            <div class="pad">
              <span></span><button data-key="UP" aria-label="${h(R.string.phone_web_key_up)}">&#9650;</button><span></span>
              <button data-key="LEFT" aria-label="${h(R.string.phone_web_key_left)}">&#9664;</button><button class="ok" data-key="OK">${h(R.string.phone_web_key_ok)}</button><button data-key="RIGHT" aria-label="${h(R.string.phone_web_key_right)}">&#9654;</button>
              <button data-key="BACK" aria-label="${h(R.string.phone_web_key_back)}">&#8617;</button><button data-key="DOWN" aria-label="${h(R.string.phone_web_key_down)}">&#9660;</button><button data-key="PLAY_PAUSE" aria-label="${h(R.string.phone_web_key_play_pause)}">&#9199;</button>
            </div>
            <div class="row"><button class="secondary" data-key="REWIND"><span aria-hidden="true">&#9194;</span> ${h(R.string.phone_web_key_rewind)}</button><button class="secondary" data-key="FAST_FORWARD">${h(R.string.phone_web_key_forward)} <span aria-hidden="true">&#9193;</span></button></div>
            <h2>${h(R.string.phone_web_keyboard_heading)}</h2>
            <label for="text">${h(R.string.phone_web_keyboard_label)}</label>
            <input id="text" autocomplete="off" autocapitalize="off" spellcheck="false" maxlength="500">
            <div class="row">
              <button id="send">${h(R.string.phone_web_type_on_tv)}</button>
              <button class="secondary" data-key="DELETE"><span aria-hidden="true">&#9003;</span> ${h(R.string.phone_web_key_delete)}</button>
            </div>
            <div class="row">
              <button class="secondary" id="search">${h(R.string.phone_web_search_on_tv)}</button>
              <button class="secondary" id="settingsSearch">${h(R.string.phone_web_search_settings)}</button>
            </div>
            <div id="status" class="status" role="status"></div>
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
              const done = type === 'search' ? ${j(R.string.phone_web_searching_on_tv)}
                : type === 'settings_search' ? ${j(R.string.phone_web_searching_settings)} : ${j(R.string.phone_web_sent)};
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
        title = h(R.string.phone_web_accounts_title),
        body = """
            <div id="loadStatus" class="status" role="status"></div>
            <h2>${h(R.string.phone_web_mdblist_heading)}</h2>
            <label for="mdbKey">${h(R.string.phone_web_mdblist_key)}</label>
            <input id="mdbKey" type="password" autocomplete="off">
            <div class="row"><button id="saveMdb">${h(R.string.phone_web_mdblist_save)}</button></div>
            <div id="mdbStatus" class="status" role="status"></div>

            <h2>${h(R.string.phone_web_trackers_heading)}</h2>
            <div class="row"><button id="trakt">${h(R.string.phone_web_trakt_sign_in)}</button><button id="simkl">${h(R.string.phone_web_simkl_sign_in)}</button></div>
            <div id="trackerCode"></div>
            <div id="trackerStatus" class="status" role="status"></div>

            <h2>${h(R.string.phone_web_server_heading)}</h2>
            <label for="serverProvider">${h(R.string.phone_web_server_type)}</label>
            <select id="serverProvider"></select>
            <label for="serverAddress">${h(R.string.phone_web_server_address)}</label>
            <input id="serverAddress" placeholder="http://192.168.1.10:8096" autocomplete="off" autocapitalize="off">
            <label for="serverUser">${h(R.string.phone_web_server_username)}</label>
            <input id="serverUser" autocomplete="username" autocapitalize="off">
            <label for="serverPassword">${h(R.string.phone_web_server_password)}</label>
            <input id="serverPassword" type="password" autocomplete="current-password">
            <div class="row"><button id="connectServer">${h(R.string.phone_web_server_connect)}</button></div>
            <div id="serverStatus" class="status" role="status"></div>
        """,
        script = """
            const SEPARATOR = ${j(R.string.phone_web_list_separator)};
            const CONNECTED_LIST = ${j(R.string.phone_web_connected_list)};
            function refresh() {
              load('/api/accounts').then(function (s) {
                show('loadStatus', '');
                show('mdbStatus', s.mdbList ? ${j(R.string.phone_web_mdblist_key_set)} : '');
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
                show('loadStatus', e.message === TV_UNREACHABLE ? e.message : ${j(R.string.phone_web_accounts_load_failed)}, true);
              });
            }
            document.getElementById('saveMdb').addEventListener('click', function () {
              show('mdbStatus', ${j(R.string.phone_web_checking)});
              post('/api/accounts/mdblist', { apiKey: document.getElementById('mdbKey').value })
                .then(function (d) { document.getElementById('mdbKey').value = ''; show('mdbStatus', d.message || ${j(R.string.phone_web_saved)}); refresh(); })
                .catch(function (e) { show('mdbStatus', e.message, true); });
            });
            function startTracker(tracker) {
              show('trackerStatus', ${j(R.string.phone_web_starting)});
              post('/api/accounts/tracker', { tracker: tracker }).then(function (d) {
                const link = '<a href="' + escapeHtml(d.verificationUrl) + '" target="_blank" rel="noopener" style="color:#8ab4ff">' + escapeHtml(d.verificationUrl) + '</a>';
                document.getElementById('trackerCode').innerHTML =
                  '<p>' + fmt(escapeHtml(${j(R.string.phone_web_tracker_open)}), link) + '</p>' +
                  '<p class="code">' + escapeHtml(d.userCode) + '</p>';
                show('trackerStatus', ${j(R.string.phone_web_tracker_waiting)});
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
              show('serverStatus', ${j(R.string.phone_web_connecting)});
              post('/api/accounts/mediaserver', {
                provider: document.getElementById('serverProvider').value,
                address: document.getElementById('serverAddress').value,
                username: document.getElementById('serverUser').value,
                password: document.getElementById('serverPassword').value
              }).then(function (d) { document.getElementById('serverPassword').value = ''; show('serverStatus', d.message || ${j(R.string.phone_web_connected)}); refresh(); })
                .catch(function (e) { show('serverStatus', e.message, true); });
            });
            refresh();
        """
    )

    private fun checkRow(id: String, @StringRes label: Int) =
        """<div class="check"><input type="checkbox" id="$id"><label for="$id">${h(label)}</label></div>"""

    // A fieldset, so each checkbox is read with the name of its list.
    private fun choiceGroup(id: String, @StringRes legend: Int) =
        """<fieldset><legend>${h(legend)}</legend><div class="checks" id="$id"></div></fieldset>"""

    private fun field(id: String, @StringRes label: Int, control: String) =
        """<label for="$id">${h(label)}</label>$control"""

    fun ranking(): String = page(
        title = h(R.string.phone_web_ranking_title),
        body = """
            <p class="hint">${h(R.string.phone_web_ranking_intro)}</p>
            ${field("preferredResolution", R.string.phone_web_ranking_preferred_resolution, """<select id="preferredResolution"></select>""")}
            ${field("maxResolution", R.string.phone_web_ranking_max_resolution, """<select id="maxResolution"></select>""")}
            ${field("minResolution", R.string.phone_web_ranking_min_resolution, """<select id="minResolution"></select>""")}
            ${field("maxSizeGb", R.string.phone_web_ranking_max_size, """<input id="maxSizeGb" type="number" min="0" step="0.5">""")}
            ${field("preferredMaxSizeGb", R.string.phone_web_ranking_preferred_max_size, """<input id="preferredMaxSizeGb" type="number" min="0" step="0.5">""")}
            ${checkRow("preferHdr", R.string.phone_web_ranking_prefer_hdr)}
            ${checkRow("excludeHdr", R.string.phone_web_ranking_exclude_hdr)}
            ${checkRow("excludeDolbyVision", R.string.phone_web_ranking_exclude_dv)}
            ${checkRow("excludeCamAndTelesync", R.string.phone_web_ranking_exclude_cam)}
            ${checkRow("exclude3d", R.string.phone_web_ranking_exclude_3d)}
            ${checkRow("preferCachedDebrid", R.string.phone_web_ranking_prefer_cached)}
            ${checkRow("preferOwnLibrary", R.string.phone_web_ranking_prefer_own_library)}
            ${checkRow("dropUndecodable", R.string.phone_web_ranking_drop_undecodable)}
            ${field("preferredKeywords", R.string.phone_web_ranking_preferred_keywords, """<textarea id="preferredKeywords" rows="3"></textarea>""")}
            ${field("excludedKeywords", R.string.phone_web_ranking_excluded_keywords, """<textarea id="excludedKeywords" rows="3"></textarea>""")}
            ${field("minSizeGb", R.string.phone_web_ranking_min_size, """<input id="minSizeGb" type="number" min="0" step="0.5">""")}
            ${choiceGroup("preferredLanguages", R.string.phone_web_ranking_preferred_languages)}
            ${choiceGroup("requiredLanguages", R.string.phone_web_ranking_required_languages)}
            ${choiceGroup("excludedLanguages", R.string.phone_web_ranking_excluded_languages)}
            ${choiceGroup("excludedResolutions", R.string.phone_web_ranking_excluded_resolutions)}
            ${choiceGroup("excludedSources", R.string.phone_web_ranking_excluded_sources)}
            ${choiceGroup("excludedCodecs", R.string.phone_web_ranking_excluded_codecs)}
            ${choiceGroup("excludedAudio", R.string.phone_web_ranking_excluded_audio)}
            ${field("preferredReleaseGroups", R.string.phone_web_ranking_preferred_groups, """<textarea id="preferredReleaseGroups" rows="3"></textarea>""")}
            ${field("excludedReleaseGroups", R.string.phone_web_ranking_excluded_groups, """<textarea id="excludedReleaseGroups" rows="3"></textarea>""")}
            ${field("preferredAddons", R.string.phone_web_ranking_preferred_addons, """<textarea id="preferredAddons" rows="3"></textarea>""")}
            ${field("maxResults", R.string.phone_web_ranking_max_results, """<input id="maxResults" type="number" min="0" step="1">""")}
            ${field("maxPerResolution", R.string.phone_web_ranking_max_per_resolution, """<input id="maxPerResolution" type="number" min="0" step="1">""")}
            ${field("maxPerSource", R.string.phone_web_ranking_max_per_source, """<input id="maxPerSource" type="number" min="0" step="1">""")}
            <div class="row"><button id="save" disabled>${h(R.string.phone_web_save)}</button></div>
            <div id="status" class="status" role="status"></div>
        """,
        script = """
            const RESOLUTIONS = ${resolutionOptionsJs()};
            const BOOLEANS = ['preferHdr','excludeHdr','excludeDolbyVision','excludeCamAndTelesync','exclude3d','preferCachedDebrid','preferOwnLibrary','dropUndecodable'];
            const CHOICES = {
              preferredLanguages: ${enumOptionsJs(StreamLanguage.entries) { it.label }},
              requiredLanguages: ${enumOptionsJs(StreamLanguage.entries) { it.label }},
              excludedLanguages: ${enumOptionsJs(StreamLanguage.entries) { it.label }},
              excludedResolutions: ${enumOptionsJs(StreamResolution.entries.filter { it.label.isNotBlank() }) { it.label }},
              excludedSources: ${enumOptionsJs(ReleaseSource.entries.filter { it.label.isNotBlank() }) { it.label }},
              excludedCodecs: ${enumOptionsJs(VideoCodec.entries.filter { it.label.isNotBlank() }) { it.label }},
              excludedAudio: ${enumOptionsJs(AudioFormat.entries) { it.label }}
            };
            const NUMBERS = ['maxResults','maxPerResolution','maxPerSource'];
            Object.keys(CHOICES).forEach(function (id) {
              const box = document.getElementById(id);
              CHOICES[id].forEach(function (c) {
                const label = document.createElement('label');
                label.className = 'check';
                const input = document.createElement('input');
                input.type = 'checkbox'; input.value = c[0]; input.dataset.group = id;
                label.appendChild(input); label.appendChild(document.createTextNode(c[1]));
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
              show('status', e.message === TV_UNREACHABLE ? e.message : ${j(R.string.phone_web_ranking_load_failed)}, true);
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
              post('/api/ranking', { rules: rules }).then(function () { current = rules; show('status', ${j(R.string.phone_web_saved)}); })
                .catch(function (e) { show('status', e.message, true); });
            });
        """
    )

    /**
     * Signing the TV in to a Fetch website from the phone: the address is typed here, where
     * there is a keyboard, and once the TV has a code this page opens the website's confirm
     * page with it filled in. The TV finishes pairing by itself once it is confirmed.
     */
    fun website(): String = page(
        title = h(R.string.phone_web_card_website),
        body = """
            <p class="hint">${h(R.string.phone_web_website_intro)}</p>
            <div id="signedIn" class="status" role="status"></div>
            <form id="form">
              <label for="address">${h(R.string.phone_web_website_address)}</label>
              <input id="address" type="url" inputmode="url" autocomplete="url" autocapitalize="off" spellcheck="false" placeholder="fetch.example.com">
              <div class="row"><button id="connect" type="submit">${h(R.string.phone_web_website_connect)}</button></div>
            </form>
            <div id="status" class="status" role="status"></div>
            <div id="codeBox" hidden>
              <p class="hint">${h(R.string.phone_web_website_code)}</p>
              <p id="code" class="code"></p>
              <div class="row"><a id="confirm" class="card" hidden>${h(R.string.phone_web_website_confirm)}</a></div>
            </div>
        """,
        script = """
            const FINDING = ${j(R.string.phone_web_website_finding)};
            const NOT_FOUND = ${j(R.string.phone_web_website_not_found)};
            const INVALID = ${j(R.string.phone_web_website_invalid)};
            const OPENING = ${j(R.string.phone_web_website_opening)};
            const EXPIRED = ${j(R.string.phone_web_website_expired)};
            const PAIRED = ${j(R.string.phone_web_website_paired)};
            const button = document.getElementById('connect');
            const input = document.getElementById('address');
            function render(d) {
              document.getElementById('signedIn').textContent = d.signedIn ? fmt(PAIRED, d.signedIn) : '';
              if (d.lastAddress && !input.value) input.value = d.lastAddress;
            }
            function failed(d) {
              if (d.state === 'expired') return EXPIRED;
              if (d.notFound) return NOT_FOUND;
              return d.message || INVALID;
            }
            document.getElementById('form').addEventListener('submit', function (event) {
              event.preventDefault();
              const address = input.value.trim();
              if (!address) { show('status', INVALID, true); return; }
              button.disabled = true;
              document.getElementById('codeBox').hidden = true;
              show('status', FINDING);
              post('/api/website', { address: address }).then(function (d) {
                if (d.state !== 'code') { show('status', failed(d), true); return; }
                document.getElementById('code').textContent = d.code;
                document.getElementById('codeBox').hidden = false;
                const link = document.getElementById('confirm');
                if (d.confirmUrl) {
                  link.href = d.confirmUrl;
                  link.hidden = false;
                  show('status', OPENING);
                  window.location.href = d.confirmUrl;
                } else {
                  link.hidden = true;
                  show('status', '');
                }
              }).catch(function (e) { show('status', e.message, true); })
                .finally(function () { button.disabled = false; });
            });
            load('/api/website').then(render).catch(function () {});
        """
    )

    fun profiles(): String = page(
        title = h(R.string.phone_web_profiles_title),
        body = """
            <p class="hint">${h(R.string.phone_web_profiles_intro)}</p>
            <h2>${h(R.string.phone_web_profiles_save_heading)}</h2>
            <div id="profiles"></div>
            <div id="exportStatus" class="status" role="status"></div>
            <h2>${h(R.string.phone_web_profiles_add_heading)}</h2>
            <label for="file">${h(R.string.phone_web_profiles_file_label)}</label>
            <input id="file" type="file" accept=".json,application/json">
            <div class="row"><button id="import">${h(R.string.phone_web_profiles_add)}</button></div>
            <div id="importStatus" class="status" role="status"></div>
        """,
        script = """
            const SAVE_AS_FILE = ${j(R.string.phone_web_profiles_save_as_file)};
            function refresh() {
              load('/api/profiles').then(function (d) {
                const box = document.getElementById('profiles');
                box.innerHTML = d.profiles.map(function (p) {
                  return '<a class="card" download href="/api/profiles/export?id=' + encodeURIComponent(p.id) + '">' +
                    escapeHtml(p.name) + '<span>' + escapeHtml(SAVE_AS_FILE) + '</span></a>';
                }).join('');
                show('exportStatus', d.profiles.length ? '' : ${j(R.string.phone_web_profiles_none)});
              }).catch(function (e) {
                show('exportStatus', e.message === TV_UNREACHABLE ? e.message : ${j(R.string.phone_web_profiles_load_failed)}, true);
              });
            }
            document.getElementById('import').addEventListener('click', function () {
              const file = document.getElementById('file').files[0];
              if (!file) { show('importStatus', ${j(R.string.phone_web_profiles_choose_file)}, true); return; }
              show('importStatus', ${j(R.string.phone_web_profiles_adding)});
              file.text().then(function (text) {
                let document_;
                try { document_ = JSON.parse(text); } catch (e) { throw new Error(${j(R.string.phone_web_profiles_not_a_profile)}); }
                return post('/api/profiles/import', document_);
              }).then(function (d) { show('importStatus', d.message || ${j(R.string.phone_web_profiles_added)}); refresh(); })
                .catch(function (e) { show('importStatus', e.message, true); });
            });
            refresh();
        """
    )

    fun backup(): String = page(
        title = h(R.string.phone_web_backup_title),
        body = """
            <p class="hint">${h(R.string.phone_web_backup_intro)}</p>
            <h2>${h(R.string.phone_web_backup_save_heading)}</h2>
            ${checkRow("addons", R.string.phone_web_backup_include_addons)}
            <p class="hint">${h(R.string.phone_web_backup_include_addons_hint)}</p>
            <a id="save" class="card" download href="/api/backup">${h(R.string.phone_web_backup_save)}<span>${h(R.string.phone_web_backup_save_sub)}</span></a>
            <h2>${h(R.string.phone_web_backup_restore_heading)}</h2>
            <p class="hint">${h(R.string.phone_web_backup_restore_hint)}</p>
            <label for="file">${h(R.string.phone_web_backup_file_label)}</label>
            <input id="file" type="file" accept=".json,application/json">
            <div class="row"><button id="restore">${h(R.string.phone_web_backup_restore)}</button></div>
            <div id="restoreStatus" class="status" role="status"></div>
        """,
        script = """
            document.getElementById('addons').addEventListener('change', function (e) {
              document.getElementById('save').setAttribute('href', e.target.checked ? '/api/backup?addons=1' : '/api/backup');
            });
            document.getElementById('restore').addEventListener('click', function () {
              const file = document.getElementById('file').files[0];
              if (!file) { show('restoreStatus', ${j(R.string.phone_web_backup_choose_file)}, true); return; }
              show('restoreStatus', ${j(R.string.phone_web_backup_restoring)});
              file.text().then(function (text) {
                let backup;
                try { backup = JSON.parse(text); } catch (e) { throw new Error(${j(R.string.phone_web_backup_not_a_backup)}); }
                if (!backup || backup.format !== 'fetch-backup') throw new Error(${j(R.string.phone_web_backup_not_a_backup)});
                return post('/api/backup/restore', backup);
              }).then(function (d) { show('restoreStatus', d.message || ''); })
                .catch(function (e) { show('restoreStatus', e.message, true); });
            });
        """
    )

    fun addons(): String = page(
        title = h(R.string.phone_web_addons_title),
        body = """
            <p class="hint">${h(R.string.phone_web_addons_intro)}</p>
            <label for="url">${h(R.string.phone_web_addons_link_label)}</label>
            <input id="url" type="url" inputmode="url" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="https://example.com/manifest.json">
            <div class="row"><button id="send">${h(R.string.phone_web_addons_send)}</button></div>
            <div id="status" class="status" role="status"></div>
            <div id="configure"></div>
            <div id="suggestionsBlock" hidden>
              <h2>${h(R.string.phone_web_addons_suggestions)}</h2>
              <div id="suggestions"></div>
            </div>
            <h2>${h(R.string.phone_web_addons_on_tv)}</h2>
            <div id="installed" class="status"></div>
        """,
        script = """
            function safeLink(url) {
              return /^https?:\/\//i.test(url) ? url : '#';
            }
            function refresh() {
              load('/api/addons').then(function (d) {
                const installed = document.getElementById('installed');
                installed.removeAttribute('role');
                installed.className = 'status';
                installed.textContent = d.summary;
                const box = document.getElementById('suggestions');
                document.getElementById('suggestionsBlock').hidden = !d.suggestions.length;
                // Buttons, not links: a suggestion sends the addon to the TV.
                box.innerHTML = d.suggestions.map(function (s, i) {
                  return '<button type="button" class="card" data-index="' + i + '">' + escapeHtml(s.name) + '<span>' + escapeHtml(s.description) + '</span></button>';
                }).join('');
                box.querySelectorAll('[data-index]').forEach(function (el) {
                  el.addEventListener('click', function () {
                    const s = d.suggestions[Number(el.dataset.index)];
                    document.getElementById('url').value = s.url;
                    send();
                  });
                });
              }).catch(function (e) {
                show('installed', e.message === TV_UNREACHABLE ? e.message : ${j(R.string.phone_web_addons_load_failed)}, true);
              });
            }
            let pollTimer = null;
            function showConfigure(d, required) {
              const box = document.getElementById('configure');
              if (!d.configureUrl) { box.innerHTML = ''; return; }
              const name = d.name || ${j(R.string.phone_web_addons_this_addon)};
              const text = fmt(required ? ${j(R.string.phone_web_addons_setup_required)} : ${j(R.string.phone_web_addons_setup_optional)}, name);
              box.innerHTML = '<p class="hint">' + escapeHtml(text) + '</p>' +
                '<a class="card" target="_blank" rel="noopener" href="' + escapeHtml(safeLink(d.configureUrl)) + '">' +
                escapeHtml(${j(R.string.phone_web_addons_open_setup)}) + '<span>' + escapeHtml(d.configureUrl) + '</span></a>';
            }
            function handle(d) {
              clearTimeout(pollTimer);
              switch (d.status) {
                case 'pending':
                  show('status', d.name ? fmt(${j(R.string.phone_web_addons_confirm_named)}, d.name) : ${j(R.string.phone_web_addons_confirm)});
                  showConfigure(d, false);
                  pollTimer = setTimeout(function () {
                    fetch('/api/addons/status?id=' + encodeURIComponent(d.id)).then(function (r) { return r.json(); }).then(handle)
                      .catch(function () { handle(d); });
                  }, 1500);
                  break;
                case 'installed':
                  show('status', d.message || (d.name ? fmt(${j(R.string.phone_web_addons_installed_named)}, d.name) : ${j(R.string.phone_web_addons_installed_generic)}));
                  document.getElementById('url').value = '';
                  document.getElementById('configure').innerHTML = '';
                  refresh();
                  break;
                case 'rejected':
                  show('status', ${j(R.string.phone_web_addons_declined)}, true);
                  break;
                case 'needs_configuration':
                  show('status', d.message || ${j(R.string.phone_web_addons_needs_setup)}, true);
                  showConfigure(d, true);
                  break;
                default:
                  show('status', d.message || ${j(R.string.phone_web_addons_failed)}, true);
              }
            }
            function send() {
              const url = document.getElementById('url').value.trim();
              if (!url) { show('status', ${j(R.string.phone_web_addons_paste_first)}, true); return; }
              show('status', ${j(R.string.phone_web_addons_reading)});
              document.getElementById('configure').innerHTML = '';
              post('/api/addons', { url: url }).then(handle).catch(function (e) { show('status', e.message, true); });
            }
            document.getElementById('send').addEventListener('click', send);
            document.getElementById('url').addEventListener('keydown', function (e) { if (e.key === 'Enter') send(); });
            refresh();
        """
    )

    private fun <T : Enum<T>> enumOptionsJs(values: List<T>, label: (T) -> String): String =
        values.joinToString(prefix = "[", postfix = "]") { value -> "[${jsString(value.name)},${jsString(label(value))}]" }

    private fun resolutionOptionsJs(): String {
        val any = resources.getString(R.string.phone_web_ranking_any)
        return StreamResolution.entries.joinToString(prefix = "[", postfix = "]") { resolution ->
            "[${jsString(resolution.name)},${jsString(resolution.label.ifBlank { any })}]"
        }
    }

    companion object {
        /** The link back to the landing page that [PhoneHubServer] adds to the section pages. */
        fun backLink(label: String): String =
            """<a href="/" style="display:block;padding:12px 16px;color:#9ab;font:15px system-ui,sans-serif;text-decoration:none">&larr; ${escapeHtml(label)}</a>"""

        internal fun escapeHtml(value: String): String = buildString(value.length) {
            value.forEach { c ->
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(c)
                }
            }
        }

        /** A JSON string literal that is also safe inside a `<script>` element. */
        internal fun jsString(value: String): String = JsonPrimitive(value).toString().replace("</", "<\\/")
    }
}
