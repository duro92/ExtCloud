if (typeof window.saveTimeInitialized === 'undefined') {
    window.saveTimeInitialized = false;
}

function initializeSaveTime(playerInstance) {
    if (window.saveTimeInitialized) {
        return;
    }
    window.saveTimeInitialized = true;

    const player = playerInstance || window.jwPlayer || window.player;
    if (!player || typeof videoId === 'undefined') {
        return;
    }

    if (typeof Storage === 'undefined') {
        Swal.fire({
            title: 'Browser Tidak Didukung',
            text: 'Fitur save time memerlukan browser yang mendukung localStorage',
            icon: 'warning'
        });
        return;
    }

    const SAVE_KEY = `kotakanime_time_${videoId}`;
    let saveInterval;
    let hasShownResumeDialog = false;
    let resumeDialogActive = false;

    window.getSavedTime = function() {
        const savedData = localStorage.getItem(SAVE_KEY);
        if (savedData) {
            try {
                const data = JSON.parse(savedData);
                return data.time || 0;
            } catch (e) {
                return 0;
            }
        }
        return 0;
    };

    window.saveTimeDialogActive = function() {
        return resumeDialogActive;
    };

    window.onRetryCompleted = function(position) {
        const savedTime = window.getSavedTime();
        if (Math.abs(position - savedTime) > 5) {
            updateSavedTime(position);
        }
    };

    function updateSavedTime(newTime) {
        const saveData = {
            time: newTime,
            duration: player.getDuration(),
            timestamp: Date.now(),
            title: document.title,
            url: window.location.href,
            trigger: 'retry_sync'
        };
        localStorage.setItem(SAVE_KEY, JSON.stringify(saveData));
    }

    function checkSavedTime() {
        const savedData = localStorage.getItem(SAVE_KEY);
        if (savedData) {
            try {
                const data = JSON.parse(savedData);
                const savedTime = data.time || 0;
                if (savedTime > 10) {
                    setTimeout(() => {
                        const hlsRetryActive = typeof window.hlsRetryActive === 'function' && window.hlsRetryActive();
                        const defaultRetryActive = typeof window.defaultRetryActive === 'function' && window.defaultRetryActive();
                        if (hlsRetryActive || defaultRetryActive) {
                            setTimeout(() => checkSavedTime(), 3000);
                            return;
                        }
                        showResumeDialog(data);
                    }, 2000);
                }
            } catch (e) {
                localStorage.removeItem(SAVE_KEY);
            }
        }
    }

    player.on('play', function() {
        startSaveInterval();
    });

    player.on('pause', function() {
        stopSaveInterval();
        saveCurrentPosition('pause');
    });

    player.on('complete', function() {
        clearSavedTime();
        Swal.fire({
            title: 'Video Selesai!',
            text: 'Terima kasih telah menonton',
            icon: 'success',
            timer: 3000,
            timerProgressBar: true,
            showConfirmButton: false
        });
    });

    function startSaveInterval() {
        if (saveInterval) {
            clearInterval(saveInterval);
        }
        saveInterval = setInterval(function() {
            saveCurrentPosition('interval');
        }, 5000);
    }

    function saveCurrentPosition(trigger) {
        try {
            const currentTime = player.getPosition();
            const duration = player.getDuration();
            const state = player.getState();
            if ((state === 'playing' && currentTime > 10 && currentTime < (duration - 60) && duration > 0) || (trigger === 'pause' && currentTime > 5)) {
                const saveData = {
                    time: currentTime,
                    duration: duration,
                    timestamp: Date.now(),
                    title: document.title,
                    url: window.location.href,
                    trigger: trigger
                };
                localStorage.setItem(SAVE_KEY, JSON.stringify(saveData));
                if (trigger === 'pause') {
                    Swal.fire({
                        title: 'Waktu Tersimpan!',
                        text: `Posisi ${formatTime(currentTime)} disimpan otomatis`,
                        icon: 'success',
                        toast: true,
                        position: 'top-end',
                        timer: 2000,
                        timerProgressBar: true,
                        showConfirmButton: false
                    });
                }
            }
        } catch (e) {
            stopSaveInterval();
        }
    }

    function stopSaveInterval() {
        if (saveInterval) {
            clearInterval(saveInterval);
            saveInterval = null;
        }
    }

    function clearSavedTime() {
        localStorage.removeItem(SAVE_KEY);
        stopSaveInterval();
    }

    function showResumeDialog(savedData) {
        if (hasShownResumeDialog || resumeDialogActive) return;
        const hlsRetryActive = typeof window.hlsRetryActive === 'function' && window.hlsRetryActive();
        if (hlsRetryActive) return;
        
        hasShownResumeDialog = true;
        resumeDialogActive = true;
        const savedTime = savedData.time;
        const currentTime = player.getPosition();
        const duration = savedData.duration || 0;
        const progress = duration > 0 ? Math.round((savedTime / duration) * 100) : 0;
        
        const timeDiff = Math.abs(savedTime - currentTime);
        if (timeDiff < 10 && currentTime > 0) {
            resumeDialogActive = false;
            return;
        }

        const wasPlaying = player.getState() === 'playing';
        if (wasPlaying) {
            player.pause();
        }
        
        Swal.fire({
            title: 'â¯ï¸ Lanjutkan Menonton?',
            html: `<div style="text-align: center; margin-bottom: 15px;"><div style="background: #f8f9fa; padding: 15px; border-radius: 8px; border-left: 4px solid #3085d6;"><div style="font-size: 16px; font-weight: bold; color: #3085d6;">${formatTime(savedTime)} / ${formatTime(duration)}</div><div style="margin-top: 5px; color: #666;">Progress: ${progress}%</div></div></div>`,
            icon: 'question',
            showCancelButton: true,
            confirmButtonText: 'â¶ï¸ Ya, Lanjutkan',
            cancelButtonText: 'ð Mulai dari Awal',
            confirmButtonColor: '#28a745',
            cancelButtonColor: '#6c757d',
            allowOutsideClick: false,
            allowEscapeKey: false,
            backdrop: 'rgba(0,0,0,0.85)'
        }).then((result) => {
            resumeDialogActive = false;
            if (result.isConfirmed) {
                player.seek(savedTime);
                player.once('seeked', function() {
                    player.play();
                });
            } else if (result.dismiss === Swal.DismissReason.cancel) {
                clearSavedTime();
                player.seek(0);
                if (wasPlaying || player.getConfig().autostart) {
                    player.play();
                }
            } else {
                if (wasPlaying) player.play();
            }
        });
    }

    function addSaveButton() {
        const saveButtonIcon = 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgd2lkdGg9IjY0IiBoZWlnaHQ9IjY0Ij48cGF0aCBmaWxsPSJub25lIiBkPSJNMCAwaDI0djI0SDB6Ii8+PHBhdGggZD0iTTE3IDNIMTdWMWgtMnYySDlWMUg3djJINHYxOGgxNlYzaC0zem0xIDE2SDZWOGgxMnYxMXptLTctNmgtMnYySDd2MmgydjJobmgtMnYyaDJ2LTJoMnYtMmgtMnYtMmgydi0yeiIgZmlsbD0icmdiYSgyNDcsMjQ3LDI0NywxKSIvPjwvc3ZnPg==';
        try {
            player.addButton(saveButtonIcon, 'Simpan Waktu', function() {
                manualSave();
            }, 'save-time-button');
        } catch (e) {
            // silent fail
        }
    }

    function manualSave() {
        try {
            const currentTime = player.getPosition();
            const duration = player.getDuration();
            const saveData = {
                time: currentTime,
                duration: duration,
                timestamp: Date.now(),
                title: document.title,
                url: window.location.href,
                trigger: 'manual'
            };
            localStorage.setItem(SAVE_KEY, JSON.stringify(saveData));
            Swal.fire({
                title: 'Waktu Tersimpan!',
                icon: 'success',
                timer: 3000,
                timerProgressBar: true,
                showConfirmButton: false
            });
        } catch (e) {
            Swal.fire({
                title: 'Error!',
                text: 'Gagal menyimpan waktu video',
                icon: 'error'
            });
        }
    }

    checkSavedTime();
    addSaveButton();
}

function formatTime(seconds) {
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const secs = Math.floor(seconds % 60);
    if (hours > 0) {
        return `${hours}:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
    }
    return `${minutes}:${secs.toString().padStart(2, '0')}`;
}
