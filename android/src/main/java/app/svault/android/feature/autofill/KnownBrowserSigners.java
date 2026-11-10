package app.svault.android.feature.autofill;

import java.util.Map;
import java.util.Set;

/**
 * 可以採信 {@code webDomain} 的 App：套件名稱 → 正式版簽署憑證的 SHA-256（大寫十六進位、沒有冒號）。
 *
 * <p>來源是 Google 給憑證管理程式用的「特權 App」清單（https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json，
 * 2026-11-10 下載，只取 {@code build=release} 的憑證；C-1）。套件名稱人人都能用，只有簽署憑證證明是原廠發佈的：
 * 使用者側載一個冒用未安裝瀏覽器名稱的 App，憑證對不上，自稱的網域就不採信。更新時重新下載同一份清單、照格式改寫。
 */
final class KnownBrowserSigners {

    /** 套件名稱 → 可接受的憑證（任一個符合即可：例如 Chrome Beta 有兩把）。 */
    static final Map<String, Set<String>> SIGNERS = Map.ofEntries(
            Map.entry("com.android.chrome", Set.of(
                    "F0FD6C5B410F25CB25C3B53346C8972FAE30F8EE7411DF910480AD6B2D60DB83")),
            Map.entry("com.chrome.beta", Set.of(
                    "DA633D34B69E63AE2103B49D53CE052FC5F7F3C53AAB94FDC2A208BDFD14249C",
                    "3D7A1223019AA39D9EA0E3436AB7C0896BFB4FB679F4DE5FE7C23F326C8F994A")),
            Map.entry("com.chrome.dev", Set.of(
                    "9044EE5FEE4BBC5E21DD44665431C4EB1F1F71A32716A0BC927BCBB39233CABF",
                    "3D7A1223019AA39D9EA0E3436AB7C0896BFB4FB679F4DE5FE7C23F326C8F994A")),
            Map.entry("com.chrome.canary", Set.of(
                    "2019DFA1FB23EFBF70C5BCD1443C5BEAB04F3F2FF4366E9AC1E3457639A24CFC")),
            Map.entry("org.chromium.chrome", Set.of(
                    "C6ADB8B83C6D4C17D292AFDE56FD488A51D316FF8F2C11C5410223BFF8A7DBB3")),
            Map.entry("org.mozilla.firefox", Set.of(
                    "A78B62A5165B4494B2FEAD9E76A280D22D937FEE6251AECE599446B2EA319B04")),
            Map.entry("org.mozilla.firefox_beta", Set.of(
                    "A78B62A5165B4494B2FEAD9E76A280D22D937FEE6251AECE599446B2EA319B04")),
            Map.entry("org.mozilla.focus", Set.of(
                    "6203A473BE36D64EE37F87FA500EDBC79EAB930610AB9B9FA4CA7D5C1F1B4FFC")),
            Map.entry("org.mozilla.fennec_aurora", Set.of(
                    "BC0488838D06F4CA6BF32386DAAB0DD8EBCF3E7730787459F62FB3CD14A1BAAA")),
            Map.entry("org.mozilla.rocket", Set.of(
                    "863A46F0973932B7D0199B549112741C2D2731AC72EA11B7523AA90A11BF5691")),
            Map.entry("org.mozilla.fenix", Set.of(
                    "5004779088E7F988D5BC5CC5F8798FEBF4F8CD084A1B2A46EFD4C8EE4AEAF211")),
            Map.entry("org.mozilla.focus.beta", Set.of(
                    "6203A473BE36D64EE37F87FA500EDBC79EAB930610AB9B9FA4CA7D5C1F1B4FFC")),
            Map.entry("org.mozilla.focus.nightly", Set.of(
                    "6203A473BE36D64EE37F87FA500EDBC79EAB930610AB9B9FA4CA7D5C1F1B4FFC")),
            Map.entry("org.mozilla.klar", Set.of(
                    "6203A473BE36D64EE37F87FA500EDBC79EAB930610AB9B9FA4CA7D5C1F1B4FFC")),
            Map.entry("org.mozilla.reference.browser", Set.of(
                    "B00990E30F9D815D2EBC7B9BB221CE47E5C9D517AAC70E7FD595B1E53E9A4B14")),
            Map.entry("com.microsoft.emmx.canary", Set.of(
                    "01E1999710A82C2749B4D50C445DC85D670B6136089D0A766A73827C82A1EAC9")),
            Map.entry("com.microsoft.emmx.dev", Set.of(
                    "01E1999710A82C2749B4D50C445DC85D670B6136089D0A766A73827C82A1EAC9")),
            Map.entry("com.microsoft.emmx.beta", Set.of(
                    "01E1999710A82C2749B4D50C445DC85D670B6136089D0A766A73827C82A1EAC9")),
            Map.entry("com.microsoft.emmx", Set.of(
                    "01E1999710A82C2749B4D50C445DC85D670B6136089D0A766A73827C82A1EAC9")),
            Map.entry("com.brave.browser", Set.of(
                    "9C2DB70513515FDBFBBC585B3EDF3D7123D4DC67C94FFD306361C1D79BBF18AC")),
            Map.entry("com.brave.browser_beta", Set.of(
                    "9C2DB70513515FDBFBBC585B3EDF3D7123D4DC67C94FFD306361C1D79BBF18AC")),
            Map.entry("com.brave.browser_nightly", Set.of(
                    "9C2DB70513515FDBFBBC585B3EDF3D7123D4DC67C94FFD306361C1D79BBF18AC")),
            Map.entry("app.vanadium.browser", Set.of(
                    "C6ADB8B83C6D4C17D292AFDE56FD488A51D316FF8F2C11C5410223BFF8A7DBB3")),
            Map.entry("com.vivaldi.browser", Set.of(
                    "E8A78544655BA8C09817F732768F5689B1662EC4B2BC5A0BC0EC138D33CA3D1E")),
            Map.entry("com.vivaldi.browser.snapshot", Set.of(
                    "E8A78544655BA8C09817F732768F5689B1662EC4B2BC5A0BC0EC138D33CA3D1E")),
            Map.entry("com.vivaldi.browser.sopranos", Set.of(
                    "E8A78544655BA8C09817F732768F5689B1662EC4B2BC5A0BC0EC138D33CA3D1E")),
            Map.entry("com.citrix.Receiver", Set.of(
                    "3DD112671069AB364EF9BE739AB7B5EE15E1CDE9D8757B1BF064F50C55689A49",
                    "CEB223D77709F2B6BC0B3A7836F5A5AF4CE1D355F4A72886F79DF80DC9D6122E",
                    "AAD0D457E633C3782577305BC1B2D9E38141C721DF0DAA6E29072FC41D34F0AB")),
            Map.entry("com.android.browser", Set.of(
                    "C9009D01EBF9F5D0302BC71B2FE9AA9A47A432BBA17308A3111B75D7B2149025")),
            Map.entry("com.sec.android.app.sbrowser", Set.of(
                    "34DF0E7A9F1CF1892E45C056B4973CD81CCF148A4050D11AEA4AC5A65F900A42")),
            Map.entry("com.sec.android.app.sbrowser.beta", Set.of(
                    "34DF0E7A9F1CF1892E45C056B4973CD81CCF148A4050D11AEA4AC5A65F900A42")),
            Map.entry("com.google.android.gms", Set.of(
                    "7CE83C1B71F3D572FED04C8D40C5CB10FF75E6D87D9DF6FBD53F0468C2905053",
                    "D22CC500299FB22873A01A010DE1C82FBE4D061119B94814DD301DAB50CB7678",
                    "F0FD6C5B410F25CB25C3B53346C8972FAE30F8EE7411DF910480AD6B2D60DB83",
                    "1975B2F17177BC89A5DFF31F9E64A6CAE281A53DC1D1D59B1D147FE1C82AFA00")),
            Map.entry("com.yandex.browser", Set.of(
                    "ACA405DED8B25CB2E8C6DA69425D2B4307D087C1276FC06AD5942731CCC51DBA")),
            Map.entry("com.yandex.browser.beta", Set.of(
                    "ACA405DED8B25CB2E8C6DA69425D2B4307D087C1276FC06AD5942731CCC51DBA")),
            Map.entry("com.yandex.browser.alpha", Set.of(
                    "ACA405DED8B25CB2E8C6DA69425D2B4307D087C1276FC06AD5942731CCC51DBA")),
            Map.entry("com.yandex.browser.corp", Set.of(
                    "ACA405DED8B25CB2E8C6DA69425D2B4307D087C1276FC06AD5942731CCC51DBA")),
            Map.entry("com.yandex.browser.canary", Set.of(
                    "1DA9CBAE2DCCC6A58D6C947BE94CDBB733D65DA4D1770FA14A5364CB4A28EB49")),
            Map.entry("com.yandex.browser.broteam", Set.of(
                    "1DA9CBAE2DCCC6A58D6C947BE94CDBB733D65DA4D1770FA14A5364CB4A28EB49")),
            Map.entry("com.talonsec.talon", Set.of(
                    "A3660344A6F6AFCA818CBF4396A23CCFD5ED7A781BB4A3D1850301E2F46D2383",
                    "E2A56474EA237B0667B6F52CDCE9045E24883BAED082599AA2DF0B603ACF6A3B")),
            Map.entry("com.talonsec.talon_beta", Set.of(
                    "F586627A32C89FE67E006DB18C34319E017FB3B2BED69D0101B7F943E77C48AE",
                    "9AA125D5E55E3FB0DE9672D9A95D04653F494A1EC3EE761E94C44E5D2F658E2F")),
            Map.entry("com.talonsec.intune.talon", Set.of(
                    "090C4CE620659B0400358FC83FDC9F02D2AB77C37F4C8014308EFF96BA544955")),
            Map.entry("com.duckduckgo.mobile.android.debug", Set.of(
                    "C4F09E2BD725ADF5AD920BA2802766AC164AC153B3EA9E0848B0579837F76A29")),
            Map.entry("com.duckduckgo.mobile.android", Set.of(
                    "BB7BB31C573C46A1DA7FC5C528A6ACF432108456FEEC50810C7F33694EB3D2D4")),
            Map.entry("com.naver.whale", Set.of(
                    "0B8B8523BB4AEFFA346E4BDD4FBF7D193450569AA14AAAD4ADFD94A3F7B227BB")),
            Map.entry("com.fido.fido2client", Set.of(
                    "FC98DAE63AD39626C8C67FBE83F2F06F74932A9CD146B92CECFC6A047A904386")),
            Map.entry("com.heytap.browser", Set.of(
                    "B29AA0BBDC9FD9DEF55DC56EA7D74576D5846CBCF5E5ABD305E2D9319E4F42AE",
                    "AFF8A749CF0E7D754465D0FBFA7B8D0C645E225C10C6E232ADA0D9748836B8E5",
                    "A8FEA4CAFB9332DA26B8E6810817C1DA90A5030E35A60A79E06C9097AAC6A442")),
            Map.entry("com.oplus.credential", Set.of(
                    "E49802409584CE53152A9000820A51E4FA8A723B7BCC263E335240ACF100BF9E")),
            Map.entry("io.island.Island", Set.of(
                    "D9C339AC9C3AEEE1751D858C35D9BAC5CC87B3CE763093F0F51064F5A2F69B04")),
            Map.entry("io.island.IslandCanary", Set.of(
                    "90171323456E6F39CBFDCFB256BE1DCFF3BC1C598A159330E49773D04CB9C905")),
            Map.entry("io.island.IslandBeta", Set.of(
                    "3531831A9E2B211DE6AAC3694B45836E5609B9D7D004C31B218740FB771738D1")),
            Map.entry("io.island.island.intune", Set.of(
                    "C23824154120A08FC39542ACD82AE9249478801E47FD6C662B181C28CA7E594E")),
            Map.entry("io.island.island.canary.intune", Set.of(
                    "1E1674BB79EA09FB37CF9F1B071B1D518D46030ED3EEF2C14EAD939EC6EE3A4C")),
            Map.entry("io.island.island.beta.intune", Set.of(
                    "D25EADF61CE6366CA423A47FC4DB9B8C9C8A35B4B019E8D982FBD08AD9DB495A")),
            Map.entry("net.quetta.browser", Set.of(
                    "BEFEE731126AA56E7EFDAEAF5EF3FAEA441C19CCE0CAEC426B65BBF82C594680")),
            Map.entry("cz.seznam.sbrowser", Set.of(
                    "DB9540661078836E4EB166F69EF407309E8DAE3334685EC8F6FA2F1381B9ACF6")),
            Map.entry("com.opera.mini.native", Set.of(
                    "57ACBC525F1B2EBD19196CD6F014397CC910FD18841E0AE850FEBC3E1E593FF2")),
            Map.entry("com.opera.mini.native.beta", Set.of(
                    "57ACBC525F1B2EBD19196CD6F014397CC910FD18841E0AE850FEBC3E1E593FF2")),
            Map.entry("com.zoho.primeum.stable", Set.of(
                    "A9D6D0A2AFDB15849B8CD31D51FE73B8E1B170BAA570C2F8F2A3F8652829CBBD")),
            Map.entry("ai.perplexity.comet", Set.of(
                    "8958A405401F69F5B0FB544424746C40DEC30C091F401F951F613C4835C3E5EC")),
            Map.entry("com.amazon.cloud9", Set.of(
                    "2F19ADEB284EB36F7F07786152B9A1D14B21653203AD0B04EBBF9C73AB6D7625",
                    "70D568EC6AE6F338BC1A6399A6537EE06908CA1D72FB8FF04874AB95433B250E")),
            Map.entry("net.waterfox.android.release", Set.of(
                    "2939997A2D8F07303CEB37AD6810AFEF0BDA710BE2116476E3525A7379EC2E1A")),
            Map.entry("com.opera.browser", Set.of(
                    "5D6AFBF87F652AF04647ADA0DF634CF22370900B164B09D50BD23AA2CB5285B8")),
            Map.entry("com.opera.browser.beta", Set.of(
                    "5D6AFBF87F652AF04647ADA0DF634CF22370900B164B09D50BD23AA2CB5285B8")),
            Map.entry("com.wsapp.browser", Set.of(
                    "F5219530FC8F87993B3CF50E838A9675AB3B2DC579A2A8520D99872A26B15C5F")),
            Map.entry("io.github.jqssun.helium", Set.of(
                    "095540494D53F48CA051BC86C392C46EDB29725DA5592BD8AF3C3D027B3166BB")),
            Map.entry("com.norton.neo", Set.of(
                    "C52A34DEF81EDED53D48ADD3756C7324A85B4204D565A7220F0838E3076D034B")),
            Map.entry("com.netskope.browser", Set.of(
                    "B8BBF59CBD84EB217BF43D1C50E85C5343E79369039390069DC00BA8A88FBADE")));

    private KnownBrowserSigners() {
    }
}
