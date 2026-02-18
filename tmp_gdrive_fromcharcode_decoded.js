var host = window.location.hostname;
    if(host=="juragan.movie" || host=="gdriveplayer.co"  || host=="gdpremium.online" || host=="gdriveplayer.us" || host=="gdriveplayer.org" || host=="gdriveplayer.me"  || host=="gdriveplayer.io"  || host=="gdriveplayer.to"  || host=="gdriveplayer.fun"  || host=="gdriveplayer.app"  || host=="gidplayer.online" || host=="stream18.net" || host=="gdriveplayerapi.com" || host.includes("zeydhan.me")){} else {
      jwplayer().remove();
    }
    var pass = "alsfheafsjklNIWORNiolNIOWNKLNXakjsfwnBdwjbwfkjbJjkopfjweopjASoiwnrflakefneiofrt";
    var CryptoJSAesJson = {
        stringify: function (cipherParams) {
            var j = {ct: cipherParams.ciphertext.toString(CryptoJS.enc.Base64)};
            if (cipherParams.iv) j.iv = cipherParams.iv.toString();
            if (cipherParams.salt) j.s = cipherParams.salt.toString();
            return JSON.stringify(j);
        },
        parse: function (jsonStr) {
            var j = JSON.parse(jsonStr);
            var cipherParams = CryptoJS.lib.CipherParams.create({ciphertext: CryptoJS.enc.Base64.parse(j.ct)});
            if (j.iv) cipherParams.iv = CryptoJS.enc.Hex.parse(j.iv)
            if (j.s) cipherParams.salt = CryptoJS.enc.Hex.parse(j.s)
            return cipherParams;
        }
    }
    eval(JSON.parse(CryptoJS.AES.decrypt(data, pass, {format: CryptoJSAesJson}).toString(CryptoJS.enc.Utf8)));