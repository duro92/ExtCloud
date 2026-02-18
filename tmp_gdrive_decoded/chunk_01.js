<script>
  function jwreload(){
    var link = $("video").attr("src");
    var stream = $("#stream").html();
    var subtitle = $("#subtitlez").html();
    var link = link.split("&proxy=")[0]+"&proxy="+stream;
    var poster = $("div.jw-preview.jw-reset").css('background-image').replace(/^url\(['"](.+)['"]\)/, '$1');
    jwplayer().load({
      sources : [{"file": link+"&res=360", "label":"Drive 360", "type":"mp4"}, {"file": link+"&res=720", "label":"Drive 720", "type":"mp4"}], 
      image: poster,
      tracks: [{ 
              file: '//'+window.location.hostname+'?subtitle='+subtitle,
              label: 'Defaults',
              kind: 'captions',
              'default': true 
            }]
    });
  }

   function jwnp(){
    var link = $("video").attr("src");
    var stream = $("#stream").html();
    var subtitle = $("#subtitlez").html();
    var link = link.split("&proxy=")[0]+"&proxy="+stream;
    var poster = $("div.jw-preview.jw-reset").css('background-image').replace(/^url\(['"](.+)['"]\)/, '$1');
    jwplayer().load({
      sources : [{"file": link+"&res=360", "label":"Original", "type":"mp4"}],
      image: poster,
      tracks: [{ 
              file: '//'+window.location.hostname+'?subtitle='+subtitle,
              label: 'Defaults',
              kind: 'captions',
              'default': true 
            }]
    });
  }

  function isDesktop() {
    var navigatorAgent = navigator.userAgent || navigator.vendor || window.opera;
    return !(
      /(android|bb\d+|meego).+mobile|avantgo|bada\/|blackberry|blazer|compal|elaine|fennec|hiptop|iemobile|ip(hone|od)|iris|kindle|lge |maemo|midp|mmp|mobile.+firefox|netfront|opera m(ob|in)i|palm( os)?|phone|p(ixi|re)\/|plucker|pocket|psp|series([46])0|symbian|treo|up\.(browser|link)|vodafone|wap|windows ce|xda|xiino|android|ipad|playbook|silk/i.test(
        navigatorAgent
      ) ||
      /1207|6310|6590|3gso|4thp|50[1-6]i|770s|802s|a wa|abac|ac(er|oo|s-)|ai(ko|rn)|al(av|ca|co)|amoi|an(ex|ny|yw)|aptu|ar(ch|go)|as(te|us)|attw|au(di|-m|r |s )|avan|be(ck|ll|nq)|bi(lb|rd)|bl(ac|az)|br([ev])w|bumb|bw-([nu])|c55\/|capi|ccwa|cdm-|cell|chtm|cldc|cmd-|co(mp|nd)|craw|da(it|ll|ng)|dbte|dc-s|devi|dica|dmob|do([cp])o|ds(12|-d)|el(49|ai)|em(l2|ul)|er(ic|k0)|esl8|ez([4-7]0|os|wa|ze)|fetc|fly([-_])|g1 u|g560|gene|gf-5|g-mo|go(\.w|od)|gr(ad|un)|haie|hcit|hd-([mpt])|hei-|hi(pt|ta)|hp( i|ip)|hs-c|ht(c([- _agpst])|tp)|hu(aw|tc)|i-(20|go|ma)|i230|iac([ \-/])|ibro|idea|ig01|ikom|im1k|inno|ipaq|iris|ja([tv])a|jbro|jemu|jigs|kddi|keji|kgt([ /])|klon|kpt |kwc-|kyo([ck])|le(no|xi)|lg( g|\/([klu])|50|54|-[a-w])|libw|lynx|m1-w|m3ga|m50\/|ma(te|ui|xo)|mc(01|21|ca)|m-cr|me(rc|ri)|mi(o8|oa|ts)|mmef|mo(01|02|bi|de|do|t([- ov])|zz)|mt(50|p1|v )|mwbp|mywa|n10[0-2]|n20[2-3]|n30([02])|n50([025])|n7(0([01])|10)|ne(([cm])-|on|tf|wf|wg|wt)|nok([6i])|nzph|o2im|op(ti|wv)|oran|owg1|p800|pan([adt])|pdxg|pg(13|-([1-8]|c))|phil|pire|pl(ay|uc)|pn-2|po(ck|rt|se)|prox|psio|pt-g|qa-a|qc(07|12|21|32|60|-[2-7]|i-)|qtek|r380|r600|raks|rim9|ro(ve|zo)|s55\/|sa(ge|ma|mm|ms|ny|va)|sc(01|h-|oo|p-)|sdk\/|se(c([-01])|47|mc|nd|ri)|sgh-|shar|sie([-m])|sk-0|sl(45|id)|sm(al|ar|b3|it|t5)|so(ft|ny)|sp(01|h-|v-|v )|sy(01|mb)|t2(18|50)|t6(00|10|18)|ta(gt|lk)|tcl-|tdg-|tel([im])|tim-|t-mo|to(pl|sh)|ts(70|m-|m3|m5)|tx-9|up(\.b|g1|si)|utst|v400|v750|veri|vi(rg|te)|vk(40|5[0-3]|-v)|vm40|voda|vulc|vx(52|53|60|61|70|80|81|83|85|98)|w3c([- ])|webc|whit|wi(g |nc|nw)|wmlb|wonu|x700|yas-|your|zeto|zte-/i.test(
        navigatorAgent.substr(0, 4)
      )
    );
  };

  'use strict';
  var _0xd959=["\x73\x61\x6E\x64\x62\x6F\x78","\x68\x61\x73\x41\x74\x74\x72\x69\x62\x75\x74\x65","\x66\x72\x61\x6D\x65\x45\x6C\x65\x6D\x65\x6E\x74","\x64\x61\x74\x61","\x69\x6E\x64\x65\x78\x4F\x66","\x68\x72\x65\x66","\x64\x6F\x6D\x61\x69\x6E","","\x70\x6C\x75\x67\x69\x6E\x73","\x75\x6E\x64\x65\x66\x69\x6E\x65\x64","\x6E\x61\x6D\x65\x64\x49\x74\x65\x6D","\x43\x68\x72\x6F\x6D\x65\x20\x50\x44\x46\x20\x56\x69\x65\x77\x65\x72","\x6F\x62\x6A\x65\x63\x74","\x63\x72\x65\x61\x74\x65\x45\x6C\x65\x6D\x65\x6E\x74","\x6F\x6E\x65\x72\x72\x6F\x72","\x74\x79\x70\x65","\x61\x70\x70\x6C\x69\x63\x61\x74\x69\x6F\x6E\x2F\x70\x64\x66","\x73\x65\x74\x41\x74\x74\x72\x69\x62\x75\x74\x65","\x73\x74\x79\x6C\x65","\x76\x69\x73\x69\x62\x69\x6C\x69\x74\x79\x3A\x68\x69\x64\x64\x65\x6E\x3B\x77\x69\x64\x74\x68\x3A\x30\x3B\x68\x65\x69\x67\x68\x74\x3A\x30\x3B\x70\x6F\x73\x69\x74\x69\x6F\x6E\x3A\x61\x62\x73\x6F\x6C\x75\x74\x65\x3B\x74\x6F\x70\x3A\x2D\x39\x39\x70\x78\x3B","\x64\x61\x74\x61\x3A\x61\x70\x70\x6C\x69\x63\x61\x74\x69\x6F\x6E\x2F\x70\x64\x66\x3B\x62\x61\x73\x65\x36\x34\x2C\x4A\x56\x42\x45\x52\x69\x30\x78\x4C\x67\x30\x4B\x64\x48\x4A\x68\x61\x57\x78\x6C\x63\x6A\x77\x38\x4C\x31\x4A\x76\x62\x33\x51\x38\x50\x43\x39\x51\x59\x57\x64\x6C\x63\x7A\x77\x38\x4C\x30\x74\x70\x5A\x48\x4E\x62\x50\x44\x77\x76\x54\x57\x56\x6B\x61\x57\x46\x43\x62\x33\x68\x62\x4D\x43\x41\x77\x49\x44\x4D\x67\x4D\x31\x30\x2B\x50\x6C\x30\x2B\x50\x6A\x34\x2B\x50\x6A\x34\x3D","\x61\x70\x70\x65\x6E\x64\x43\x68\x69\x6C\x64","\x62\x6F\x64\x79","\x72\x65\x6D\x6F\x76\x65\x43\x68\x69\x6C\x64","\x70\x61\x72\x65\x6E\x74\x45\x6C\x65\x6D\x65\x6E\x74","\x2F\x65\x6D\x62\x65\x64\x62\x6C\x6F\x63\x6B\x65\x64\x3F\x72\x65\x66\x65\x72\x65\x72\x3D","\x73\x75\x62\x73\x74\x72\x69\x6E\x67","\x72\x65\x66\x65\x72\x72\x65\x72"];function isSandboxed(_0x7089x2){try{if(window[_0xd959[2]][_0xd959[1]](_0xd959[0])){_0x7089x2();return}}catch(err){};if(location[_0xd959[5]][_0xd959[4]](_0xd959[3])!= 0&& document[_0xd959[6]]== _0xd959[7]){_0x7089x2();return};if( typeof navigator[_0xd959[8]]!= _0xd959[9]&&  typeof navigator[_0xd959[8]][_0xd959[10]]!= _0xd959[9]&& navigator[_0xd959[8]][_0xd959[10]](_0xd959[11])!= null){var _0x7089x3=document[_0xd959[13]](_0xd959[12]);_0x7089x3[_0xd959[14]]= function(){_0x7089x2()};_0x7089x3[_0xd959[17]](_0xd959[15],_0xd959[16]);_0x7089x3[_0xd959[17]](_0xd959[18],_0xd959[19]);_0x7089x3[_0xd959[17]](_0xd959[3],_0xd959[20]);document[_0xd959[22]][_0xd959[21]](_0x7089x3);setTimeout(function(){_0x7089x3[_0xd959[24]][_0xd959[23]](_0x7089x3)},150)}}
</script>