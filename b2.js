
            const RESOLUTIONS = [["A","a"]];
            const BOOLEANS = ['preferHdr','excludeHdr','excludeDolbyVision','excludeCamAndTelesync','exclude3d','preferCachedDebrid','preferOwnLibrary','dropUndecodable'];
            const CHOICES = {
              preferredLanguages: [["A","a"]],
              requiredLanguages: [["A","a"]],
              excludedLanguages: [["A","a"]],
              excludedResolutions: [["A","a"]],
              excludedSources: [["A","a"]],
              excludedCodecs: [["A","a"]],
              excludedAudio: [["A","a"]]
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
              show('status', e.message === TV_UNREACHABLE ? e.message : "Couldn’t load the current rules, so they can’t be saved. Reload this page.", true);
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
              post('/api/ranking', { rules: rules }).then(function () { current = rules; show('status', "Saved"); })
                .catch(function (e) { show('status', e.message, true); });
            });
        