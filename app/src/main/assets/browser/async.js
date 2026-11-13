// Result envelope from Kelivo BrowserAutomationScript.evaluate, commit 4d69846f.
// AGPL v3; native completion delivery is adapted for this Android WebView.
((id, execute) => {
  const bridge = window.__rikkaBrowser;
  (async () => {
    try {
      const value = await execute();
      bridge.complete(id, JSON.stringify({ok:true, value:value === undefined ? null : value}));
    } catch (error) {
      bridge.complete(id, JSON.stringify({ok:false, error:String(error?.message || error)}));
    }
  })();
})(__RIKKA_ID__, async () => {
__RIKKA_BODY__
});
