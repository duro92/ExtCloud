/**
 * JWPlayer Default Error Handler (v2.0)
 * Fitur:
 * - Retry otomatis hingga 5 kali untuk error 221000.
 * - Jeda waktu yang meningkat antar percobaan (1s, 2s, 4s, 8s, 16s).
 * - Notifikasi yang jelas untuk setiap percobaan retry.
 * - Reset otomatis setelah berhasil atau interaksi pengguna.
 */
function initializeDefaultHandler(player) {
    let isRetrying = false;
    let retryCount = 0;
    const MAX_RETRIES = 5;
    let lastKnownPosition = 0;

    // Fungsi untuk memulai atau melanjutkan proses retry
    function attemptRetry() {
        if (retryCount >= MAX_RETRIES) {
            console.error("Gagal memulihkan video setelah", MAX_RETRIES, "kali percobaan.");
            Swal.fire({
                title: 'Gagal Memuat Video',
                text: `Kami sudah mencoba ${MAX_RETRIES} kali tetapi video tetap tidak bisa diputar. Silakan muat ulang halaman atau periksa koneksi Anda.`,
                icon: 'error',
                confirmButtonText: 'Muat Ulang Halaman',
                confirmButtonColor: '#e74c3c'
            }).then(() => {
                window.location.reload();
            });
            resetRetryState();
            return;
        }

        retryCount++;
        const delay = Math.pow(2, retryCount - 1) * 1000; // 1s, 2s, 4s, 8s, 16s

        console.log(`Percobaan retry ke-${retryCount} akan dimulai dalam ${delay / 1000} detik.`);

        Swal.fire({
            title: 'Koneksi Bermasalah',
            html: `Mencoba menyambungkan kembali... <br>(Percobaan ${retryCount} dari ${MAX_RETRIES})`,
            icon: 'info',
            timer: delay,
            timerProgressBar: true,
            showConfirmButton: false,
            allowOutsideClick: false,
        });

        setTimeout(() => {
            console.log("Memulai ulang player...");
            const currentSource = player.getPlaylistItem();
            player.load([currentSource]);
            player.play().catch(e => console.error("Error saat mencoba play di percobaan retry:", e));
        }, delay);
    }

    // Fungsi untuk mereset status retry
    function resetRetryState() {
        isRetrying = false;
        retryCount = 0;
    }

    // Handler utama ketika error terjadi
    function handleError(event) {
        if (event.code !== 221000) {
            return; // Abaikan error selain masalah koneksi
        }
        
        // Simpan posisi terakhir sebelum error
        lastKnownPosition = player.getPosition();
        console.warn("Error 221000 terdeteksi pada posisi:", lastKnownPosition);

        if (!isRetrying) {
            isRetrying = true;
            console.log("Memulai proses auto-retry...");
            attemptRetry();
        }
    }

    // Listener ketika player berhasil play (setelah retry atau interaksi user)
    function onSuccessfulPlay() {
        if (isRetrying) {
            console.log("Video berhasil dipulihkan!");
            Swal.fire({
                title: 'Video Dipulihkan!',
                text: `Streaming dilanjutkan dari posisi terakhir.`,
                icon: 'success',
                toast: true,
                position: 'top-end',
                timer: 3000,
                timerProgressBar: true,
                showConfirmButton: false
            });
            
            // Kembalikan video ke posisi sebelum error
            if (lastKnownPosition > 1) {
                player.seek(lastKnownPosition);
            }
            resetRetryState();
        }
    }

    // Pasang semua event listener ke player
    player.on('error', handleError);
    player.on('play', onSuccessfulPlay);

    // Jika user melakukan seek manual, reset status retry
    player.on('seek', () => {
        if (isRetrying) {
            console.log("Retry dibatalkan karena interaksi pengguna.");
            resetRetryState();
            Swal.close();
        }
    });
}

// Inisialisasi handler (pastikan ini berjalan setelah player dibuat)
if (window.player) {
    initializeDefaultHandler(window.player);
} else {
    document.addEventListener('DOMContentLoaded', () => {
        if (window.player) {
            initializeDefaultHandler(window.player);
        } else {
            console.error('Player instance (window.player) not found for default error handler.');
        }
    });
}
