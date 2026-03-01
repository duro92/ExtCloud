jQuery(document).ready(function($) {
    // Handle Autoload Video ajax
    if (kotakajax.autoload === '1') {
        $.ajax({
            url: kotakajax.url,
            type: "POST",
            data: {
                action: "player_ajax",
                post: $(".serverplayer").first().data("post"),
                nume: "0",
                nonce: kotakajax.nonce
            },
            beforeSend: function() {
                $("#videoku").html('<div class="redtext tagpst1">' + kotakajax.loading + '</div>');
            },
            success: function(response) {
                $("#videoku").html(response);
            }
        });
    }
    
    function handleAjaxError(xhr, status, error) {
        console.error('Ajax error:', status, error);
        $('#videoku').html('<div class="redtext tagpst1">Terjadi kesalahan saat memuat video. Silakan coba lagi.</div>');
    }
    
    // Handle server player click
    $('.serverplayer').on('click', function() {
        var $this = $(this);
        var post_id = $this.data('post');
        var serverName = $this.data('type').toLowerCase();
        var nume = $this.data('nume');
        
        $('.serverplayer').removeClass('on current1');
        $this.addClass('on current1');
        
        $.ajax({
            url: kotakajax.url,
            type: 'POST',
            data: {
                action: 'player_ajax',
                nonce: kotakajax.nonce,
                serverName: serverName,
                nume: nume,
                post: post_id
            },
            beforeSend: function() {
                $('#videoku').html('<div class="redtext tagpst1">' + kotakajax.loading + '</div>');
            },
            success: function(response) {
                $('#videoku').html(response);
            },
            error: handleAjaxError
        });
    });

    // Tab Menu (tetap sama seperti versi sebelumnya)
    function handleTabClick(tabSelector, contentSelector, currentClass) {
        $(tabSelector).click(function() {
            var tabId = $(this).attr("data-" + contentSelector.slice(0, -1));
            $(tabSelector).removeClass(currentClass);
            $(contentSelector).removeClass(currentClass);
            $(this).addClass(currentClass);
            $("#" + tabId).addClass(currentClass);
        });
    }

    handleTabClick("ul.tabs li", "tab", "current");
    handleTabClick("ul.tabs1 li", "tab1", "current1");
    
    // Generic Auto-Hide Handler (Menggabungkan banner dan notice)
    function setupAutoHideElement(selector, options = {}) {
        const defaults = {
            closeSelector: '.close_banner, .close-indicator',
            autoHideDelay: 30000,
            fadeOutClass: 'fade-out',
            fadeOutDelay: 500,
            action: 'hide' // 'hide' atau 'remove'
        };
        
        const settings = Object.assign(defaults, options);
        
        $(selector).each(function() {
            const $element = $(this);
            const $closeBtn = $element.find(settings.closeSelector);
            
            // Skip jika sudah diinisialisasi
            if ($element.data('auto-hide-initialized')) return;
            $element.data('auto-hide-initialized', true);
            
            let autoHideTimer;
            
            const hideElement = () => {
                if (settings.action === 'remove') {
                    $element.addClass(settings.fadeOutClass);
                    setTimeout(() => {
                        $element.remove();
                    }, settings.fadeOutDelay);
                } else {
                    $element.hide();
                }
            };
            
            // Set auto-hide timer
            if (settings.autoHideDelay > 0) {
                autoHideTimer = setTimeout(hideElement, settings.autoHideDelay);
            }
            
            // Handle close button click
            $closeBtn.on('click', function(e) {
                e.preventDefault();
                if (autoHideTimer) clearTimeout(autoHideTimer);
                hideElement();
            });
        });
    }
    
    // Inisialisasi untuk banner player
    setupAutoHideElement('.banner_player', {
        closeSelector: '.close_banner',
        autoHideDelay: 30000,
        action: 'hide'
    });
    
    // Inisialisasi untuk notice (menggantikan fungsi setupNotice yang lama)
    setupAutoHideElement('.notice-iklan', {
        closeSelector: '.close-indicator',
        autoHideDelay: 30000,
        action: 'remove',
        fadeOutClass: 'fade-out',
        fadeOutDelay: 500
    });
    
    // Handle episodes span click and auto-hide
    setupAutoHideElement('.types.episodes', {
        closeSelector: '.types.episodes', // The element itself acts as the close button
        autoHideDelay: 5000, // Auto-hide after 5 seconds (adjust as needed)
        action: 'hide'
    });
    
    // Additional click handler for immediate hide on click
    $(document).on('click', '.types.episodes', function(e) {
        e.preventDefault();
        $(this).hide();
    });

    
    // Observer untuk element yang ditambahkan secara dinamis
    const targetNode = document.body;
    const config = { childList: true, subtree: true };
    const callback = function(mutationsList, observer) {
        for (const mutation of mutationsList) {
            if (mutation.type === 'childList') {
                mutation.addedNodes.forEach(node => {
                    if (node.nodeType === 1) {
                        // Check untuk banner baru
                        if (node.matches && node.matches('.banner_player')) {
                            setupAutoHideElement(node, {
                                closeSelector: '.close_banner',
                                autoHideDelay: 30000,
                                action: 'hide'
                            });
                        }
                        
                        // Check untuk notice baru
                        if (node.matches && node.matches('.notice-iklan')) {
                            setupAutoHideElement(node, {
                                closeSelector: '.close-indicator',
                                autoHideDelay: 30000,
                                action: 'remove',
                                fadeOutClass: 'fade-out',
                                fadeOutDelay: 500
                            });
                        }
                        
                        // Check untuk child elements
                        if (node.querySelectorAll) {
                            $(node).find('.banner_player').each(function() {
                                setupAutoHideElement(this, {
                                    closeSelector: '.close_banner',
                                    autoHideDelay: 30000,
                                    action: 'hide'
                                });
                            });
                            
                            $(node).find('.notice-iklan').each(function() {
                                setupAutoHideElement(this, {
                                    closeSelector: '.close-indicator',
                                    autoHideDelay: 30000,
                                    action: 'remove',
                                    fadeOutClass: 'fade-out',
                                    fadeOutDelay: 500
                                });
                            });
                        }
                    }
                });
            }
        }
    };
    
    const observer = new MutationObserver(callback);
    observer.observe(targetNode, config);
});
