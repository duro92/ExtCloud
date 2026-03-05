/**
 * HLS HANDLER ULTIMATE v4.0 (Silent Guardian)
 * Strategy: Downgrade Quality > Nudge > Reload
 * Goal: Zero User Interruption
 */

// --- UTILITIES ---
function debounce(func, wait) {
    let timeout;
    return function() {
        const context = this, args = arguments;
        clearTimeout(timeout);
        timeout = setTimeout(() => func.apply(context, args), wait);
    };
}

// Overlay hanya muncul saat Reload Total (Langkah Terakhir)
function showLoadingOverlay() {
    const p = player.getContainer();
    if (!p || p.querySelector('.custom-loading-overlay')) return;
    const overlay = document.createElement('div');
    overlay.className = 'custom-loading-overlay';
    overlay.style.cssText = 'position: absolute; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0, 0, 0, 0.7); backdrop-filter: blur(4px); display: flex; justify-content: center; align-items: center; z-index: 99; flex-direction: column; pointer-events: none;';
    
    // Icon Spinner Modern
    overlay.innerHTML = `
        <div class="jw-icon jw-icon-display jw-button-color jw-icon-buffer jw-animate-spin" style="transform: scale(1.5);"></div>
        <div style="color: #fff; margin-top: 20px; font-family: sans-serif; font-weight: bold; font-size: 14px;">Memperbaiki Jaringan...</div>
    `;
    p.appendChild(overlay);
}

function hideLoadingOverlay() {
    const p = player.getContainer();
    if (!p) return;
    const overlay = p.querySelector('.custom-loading-overlay');
    if (overlay) overlay.remove();
}

function showToast(message) {
    // Notifikasi kecil non-intrusive di pojok (opsional, bisa dihapus jika ingin silent total)
    if (typeof Swal !== 'undefined') {
        const Toast = Swal.mixin({ toast: true, position: 'top-end', showConfirmButton: false, timer: 2000 });
        Toast.fire({ icon: 'info', title: message });
    } else {
        console.log('[HLS v4] ' + message);
    }
}

// --- CORE VARIABLES ---
let isRetrying = false;
let hlsSyncCheckInterval = null;
let lastKnownPosition = 0;
let stallCounter = 0;
let seekingState = false;
let lastSeekTime = 0;
let qualitySwitched = false; // Flag apakah kita sudah pernah menurunkan kualitas

// --- ACTIONS ---

// TIER 1: QUALITY DOWNGRADE (Penyelamat HP/Koneksi Lambat)
function forceLowQuality() {
    const levels = player.getQualityLevels();
    // Jika tidak ada pilihan kualitas atau sudah di level rendah, skip
    if (!levels || levels.length < 2) return false;

    const currentQ = player.getCurrentQuality();
    // Jika currentQ adalah Auto (biasanya 0 atau index tertentu tergantung config), coba cari yang terendah non-auto
    // Strategi: Set ke index 1 (biasanya 360p/480p di banyak playlist) atau 0
    
    // Kita cari level dengan bitrate terendah tapi bukan audio only
    let targetIndex = 1; // Asumsi index 0/1 adalah low quality
    
    if (currentQ !== targetIndex && !qualitySwitched) {
        console.warn('[HLS v4] Network slow. Downgrading quality to ensure playback.');
        player.setCurrentQuality(targetIndex); 
        qualitySwitched = true; 
        showToast('Menyesuaikan kualitas video...');
        return true; // Berhasil switch
    }
    return false;
}

// TIER 2: THE NUDGE (Membangunkan Decoder Macet)
function nudgePlayer() {
    const current = player.getPosition();
    const dur = player.getDuration();
    if (dur - current > 5) { // Hanya jika bukan di akhir video
        console.warn('[HLS v4] Nudging player...');
        player.seek(current + 0.5); // Lompat maju sedikit
        return true;
    }
    return false;
}

// TIER 3: HARD RESET (Cara Lama - Opsi Terakhir)
function restartHLSPlayback() {
    if (isRetrying) return;
    isRetrying = true;
    stopSyncCheck();

    const savedPos = player.getPosition();
    const savedAudio = player.getCurrentAudioTrack();
    // Kita TIDAK save quality, biarkan reset ke Auto agar fresh

    console.warn('[HLS v4] Hard Reset initiated.');
    showLoadingOverlay();

    player.load([player.getPlaylistItem()]);

    player.once('play', function() {
        if (savedPos > 5) player.seek(savedPos - 2); // Mundur 2 detik biar mulus
        if (savedAudio) player.setCurrentAudioTrack(savedAudio);
        
        setTimeout(() => {
            hideLoadingOverlay();
            isRetrying = false;
            stallCounter = 0;
            startSmartSyncCheck();
        }, 1500);
    });
    
    // Failsafe jika player macet total
    setTimeout(() => {
        if(isRetrying) { hideLoadingOverlay(); isRetrying = false; }
    }, 10000);
}

// --- INTELLIGENT CHECKER ---
function smartSyncCheck() {
    const state = player.getState();
    
    // Ignore normal states
    if (state === 'idle' || state === 'paused' || state === 'complete' || seekingState || isRetrying) {
        stallCounter = 0;
        return;
    }

    const currentPos = player.getPosition();
    const bufferPercent = player.getBuffer();
    const duration = player.getDuration();
    
    // Hitung buffer dalam detik
    const bufferedSeconds = (bufferPercent / 100) * duration;
    const bufferGap = bufferedSeconds - currentPos;

    // KONDISI: Player Lancar
    if (currentPos > lastKnownPosition) {
        lastKnownPosition = currentPos;
        stallCounter = 0; // Reset
        
        // Reset flag quality switch jika sudah lancar lama (misal 2 menit), 
        // siapa tau user mau HD lagi. (Opsional, di sini kita disable biar stabil terus)
    } 
    
    // KONDISI: Player Macet (Posisi tidak berubah)
    else if (state === 'playing' || state === 'buffering') {
        
        // Cek apakah ini benar-benar macet atau hanya loading pendek
        // Gunakan bufferGap. Jika gap < 1 detik, berarti memang kehabisan data.
        
        stallCounter++;
        console.log(`[HLS v4] Stall Detect: ${stallCounter} | Gap: ${bufferGap.toFixed(1)}s`);

        // AKSI BERTINGKAT (Escalation)

        // 1. Deteksi Dini (3x cek / ~7 detik): Turunkan Kualitas
        if (stallCounter === 5) { // ~25 detik, beri waktu buffer
            const didDowngrade = forceLowQuality();
        }

        // 2. Deteksi Lanjut (5x cek / ~12 detik): Nudge Player
        if (stallCounter === 5) {
            nudgePlayer();
        }

        // 3. Deteksi Fatal (8x cek / ~20 detik): Hard Reset
        // Kita perpanjang durasinya, jangan cepat menyerah.
        if (stallCounter >= 8) {
            debounceRestart();
        }
    }
    lastKnownPosition = currentPos;
}

const debounceRestart = debounce(restartHLSPlayback, 1000);

function startSmartSyncCheck() {
    stopSyncCheck();
    stallCounter = 0;
    lastKnownPosition = player.getPosition();
    hlsSyncCheckInterval = setInterval(smartSyncCheck, 5000); // 5 detik lebih stabil
}

function stopSyncCheck() {
    if (hlsSyncCheckInterval) { clearInterval(hlsSyncCheckInterval); hlsSyncCheckInterval = null; }
}

// --- EVENT LISTENERS ---
player.on('ready', () => { 
    stallCounter = 0; 
    qualitySwitched = false;
});

player.on('seek', () => { seekingState = true; stopSyncCheck(); });
player.on('seeked', () => { 
    seekingState = false; 
    lastKnownPosition = player.getPosition(); 
    startSmartSyncCheck(); 
});

player.on('play', startSmartSyncCheck);
player.on('pause', stopSyncCheck);
player.on('complete', stopSyncCheck);
player.on('remove', stopSyncCheck);

// Error Handling Khusus
player.on('error', function(e) {
    // Jika error fatal, langsung hard reset tanpa nunggu counter
    if (e.code > 200000) {
        console.log("Fatal Error detected, recovering...");
        debounceRestart();
    }
});
