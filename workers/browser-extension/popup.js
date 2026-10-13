document.getElementById("open").addEventListener("click", () => {
  void chrome.tabs.create({ url: "https://xiaod.dpdns.org/profile" });
});
