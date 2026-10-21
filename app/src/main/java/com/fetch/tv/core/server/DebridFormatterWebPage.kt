package com.fetch.tv.core.server

import android.content.Context
import android.content.res.Configuration
import com.fetch.tv.R
import java.util.Locale

object DebridFormatterWebPage {
    fun html(rawContext: Context?): String {
        val context = rawContext?.let { base ->
            val tag = base.getSharedPreferences("app_locale", Context.MODE_PRIVATE)
                .getString("locale_tag", null)
            if (!tag.isNullOrEmpty()) {
                val config = Configuration(base.resources.configuration)
                config.setLocale(Locale.forLanguageTag(tag))
                base.createConfigurationContext(config)
            } else base
        }
        fun s(id: Int, fallback: String): String = context?.getString(id) ?: fallback
        val appName = context?.getString(R.string.app_name) ?: "Fetch"
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<title>$appName - ${s(R.string.web_debrid_title, "Direct Debrid Settings").html()}</title>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800;900&display=swap" rel="stylesheet">
<style>
  * {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
    -webkit-tap-highlight-color: transparent;
  }
  *:focus, *:active { outline: none !important; }
  body {
    font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    background: #000;
    color: #fff;
    min-height: 100vh;
    line-height: 1.5;
  }
  .page {
    max-width: 600px;
    margin: 0 auto;
    padding: 0 1.5rem 6rem;
  }
  .header {
    text-align: center;
    padding: 3rem 0 2.5rem;
    border-bottom: 1px solid rgba(255, 255, 255, 0.05);
    margin-bottom: 2.5rem;
  }
  .header-logo {
    height: 40px;
    width: auto;
    margin-bottom: 0.5rem;
    filter: brightness(0) invert(1);
    opacity: 0.9;
  }
  .header p {
    font-size: 0.875rem;
    font-weight: 300;
    color: rgba(255, 255, 255, 0.4);
    letter-spacing: 0.02em;
  }
  .intro {
    margin-bottom: 2.5rem;
  }
  .intro-title {
    font-size: 1rem;
    font-weight: 700;
    letter-spacing: -0.01em;
    margin-bottom: 0.35rem;
  }
  .intro-copy {
    color: rgba(255, 255, 255, 0.42);
    font-size: 0.875rem;
    font-weight: 300;
  }
  .tabs {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0.5rem;
    margin-bottom: 2rem;
  }
  .tab {
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 999px;
    background: transparent;
    color: rgba(255, 255, 255, 0.58);
    font-family: inherit;
    font-size: 0.875rem;
    font-weight: 600;
    padding: 0.85rem 1rem;
    cursor: pointer;
    transition: all 0.25s ease;
  }
  .tab.active {
    background: #fff;
    border-color: #fff;
    color: #000;
  }
  .panel {
    display: none;
  }
  .panel.active {
    display: block;
  }
  .section-label {
    font-size: 0.75rem;
    font-weight: 500;
    color: rgba(255, 255, 255, 0.3);
    letter-spacing: 0.1em;
    text-transform: uppercase;
    margin-bottom: 1rem;
  }
  .chips {
    display: flex;
    gap: 0.5rem;
    flex-wrap: wrap;
    margin-bottom: 2.5rem;
  }
  .chip {
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 100px;
    padding: 0.45rem 0.7rem;
    color: rgba(255, 255, 255, 0.55);
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 0.72rem;
  }
  .field {
    margin-bottom: 1.5rem;
  }
  .field label {
    display: block;
    font-size: 0.75rem;
    font-weight: 500;
    color: rgba(255, 255, 255, 0.3);
    letter-spacing: 0.1em;
    text-transform: uppercase;
    margin-bottom: 0.75rem;
  }
  textarea {
    width: 100%;
    min-height: 150px;
    resize: vertical;
    background: transparent;
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 16px;
    padding: 0.9rem 1rem;
    color: #fff;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 0.82rem;
    line-height: 1.5;
    transition: border-color 0.3s ease;
  }
  textarea:focus {
    border-color: rgba(255, 255, 255, 0.4);
  }
  input, select {
    width: 100%;
    background: transparent;
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 12px;
    padding: 0.75rem 0.85rem;
    color: #fff;
    font-family: inherit;
    font-size: 0.875rem;
  }
  select option {
    color: #000;
  }
  #descriptionTemplate {
    min-height: 280px;
  }
  .subcopy {
    color: rgba(255, 255, 255, 0.42);
    font-size: 0.875rem;
    font-weight: 300;
    margin: -0.35rem 0 1.5rem;
  }
  .mini-btn {
    flex: 0 0 auto;
    padding: 0.5rem 0.8rem;
    font-size: 0.78rem;
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 0.75rem;
    margin-bottom: 1.5rem;
  }
  .checks {
    display: grid;
    gap: 0.5rem;
    margin-bottom: 1.5rem;
  }
  .checks-title {
    color: rgba(255, 255, 255, 0.65);
    font-size: 0.82rem;
    font-weight: 600;
    margin-bottom: 0.2rem;
  }
  .check-grid {
    display: flex;
    flex-wrap: wrap;
    gap: 0.45rem;
  }
  .check {
    display: inline-flex;
    align-items: center;
    gap: 0.4rem;
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 999px;
    padding: 0.45rem 0.65rem;
    color: rgba(255, 255, 255, 0.68);
    font-size: 0.78rem;
  }
  .check input {
    width: auto;
    accent-color: #fff;
  }
  .actions {
    display: flex;
    gap: 0.75rem;
    margin-top: 2rem;
  }
  .btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 0.5rem;
    flex: 1;
    background: transparent;
    border: 1px solid rgba(255, 255, 255, 0.2);
    border-radius: 100px;
    padding: 0.875rem 1.5rem;
    color: #fff;
    font-family: inherit;
    font-size: 0.875rem;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.3s ease;
    white-space: nowrap;
    -webkit-tap-highlight-color: transparent;
  }
  .btn:hover {
    background: #fff;
    color: #000;
    border-color: #fff;
  }
  .btn:active { transform: scale(0.97); }
  .status {
    color: rgba(135, 239, 172, 0.95);
    font-size: 0.875rem;
    font-weight: 300;
    min-height: 24px;
    margin-top: 1rem;
    text-align: center;
  }
  .status.error {
    color: rgba(207, 102, 121, 0.9);
  }
  @media (max-width: 480px) {
    .page { padding: 0 1rem 5rem; }
    .header { padding: 2rem 0 2rem; }
    .header-logo { height: 32px; }
    .grid { grid-template-columns: 1fr; }
    .actions { flex-direction: column; }
    .tabs { grid-template-columns: 1fr; }
    .tab { padding: 0.75rem 0.6rem; }
  }
</style>
</head>
<body>
<div class="page">
  <div class="header">
    <img src="/logo.png" alt="Fetch" class="header-logo">
    <p>${s(R.string.web_debrid_title, "Direct Debrid Settings").html()}</p>
  </div>

  <div class="intro">
    <div class="intro-title">${s(R.string.web_debrid_intro_title, "Customize Debrid streams").html()}</div>
    <div class="intro-copy">${s(R.string.web_debrid_intro_copy, "Adjust the stream labels used for Direct Debrid results.").html()}</div>
  </div>

  <div class="panel active" id="panel-formatter">
    <div class="section-label">${s(R.string.web_debrid_template_fields, "Template fields").html()}</div>
    <div class="chips">
      <span class="chip">{stream.resolution}</span>
      <span class="chip">{stream.quality}</span>
      <span class="chip">{stream.visualTags::join(' | ')}</span>
      <span class="chip">{stream.audioTags::join(' | ')}</span>
      <span class="chip">{stream.rseMatched::join(' | ')}</span>
      <span class="chip">{stream.size::bytes}</span>
      <span class="chip">{service.cached::istrue["Ready"||"Not Ready"]}</span>
    </div>

    <div class="field">
      <label for="nameTemplate">${s(R.string.web_debrid_name_template, "Name Template").html()}</label>
      <textarea id="nameTemplate" spellcheck="false"></textarea>
    </div>

    <div class="field">
      <label for="descriptionTemplate">${s(R.string.web_debrid_description_template, "Description Template").html()}</label>
      <textarea id="descriptionTemplate" spellcheck="false"></textarea>
    </div>
  </div>

  <div class="actions">
    <button class="btn" id="defaults">${s(R.string.web_debrid_restore_default, "Restore Default").html()}</button>
    <button class="btn" id="save">${s(R.string.web_debrid_save_settings, "Save Settings").html()}</button>
  </div>
  <div class="status" id="status"></div>
</div>
<script>
let defaults = null;
const nameBox = document.getElementById('nameTemplate');
const descBox = document.getElementById('descriptionTemplate');
const statusBox = document.getElementById('status');
function escapeHtml(value){
  return String(value||'').replace(/[&<>"']/g, ch=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
}
async function load(){
  const res = await fetch('/api/settings');
  const body = await res.json();
  defaults = body.defaults;
  nameBox.value = body.settings.nameTemplate ?? defaults.nameTemplate;
  descBox.value = body.settings.descriptionTemplate ?? defaults.descriptionTemplate;
}
async function save(){
  statusBox.textContent = '${s(R.string.web_status_saving, "Saving…").js()}';
  statusBox.className = 'status';
  const res = await fetch('/api/settings',{
    method:'POST',
    headers:{'Content-Type':'application/json; charset=utf-8'},
    body:JSON.stringify({nameTemplate:nameBox.value,descriptionTemplate:descBox.value})
  });
  if(res.ok){
    statusBox.textContent = '${s(R.string.web_status_saved_streams, "Saved. New streams will use these settings.").js()}';
  }else{
    const body = await res.json().catch(()=>({error:'${s(R.string.web_status_could_not_save, "Could not save").js()}'}));
    statusBox.textContent = body.error || '${s(R.string.web_status_could_not_save, "Could not save").js()}';
    statusBox.className = 'status error';
  }
}
document.getElementById('save').addEventListener('click',save);
document.getElementById('defaults').addEventListener('click',()=>{
  if(!defaults)return;
  nameBox.value = defaults.nameTemplate;
  descBox.value = defaults.descriptionTemplate;
});
load().catch(()=>{statusBox.textContent='${s(R.string.web_debrid_status_load_error, "Could not load Debrid settings").js()}';statusBox.className='status error';});
</script>
</body>
</html>
""".trimIndent()
    }

    private fun String.html(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun String.js(): String =
        replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
}