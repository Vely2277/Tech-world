/**
 * Construct Connect - App Download Website
 * JavaScript functionality for navigation, animations, and interactions
 */

document.addEventListener('DOMContentLoaded', function() {
    // ===== DOM Elements =====
    const header = document.querySelector('.header');
    const navToggle = document.getElementById('navToggle');
    const navMenu = document.getElementById('navMenu');
    const navLinks = document.querySelectorAll('.nav-link');
    const downloadBtn = document.getElementById('downloadBtn');

    // ===== Configuration =====
    const CONFIG = {
        // Update this URL with your actual GitHub release APK link
        downloadUrl: 'https://github.com/YOUR_USERNAME/YOUR_REPO/releases/latest/download/construct-connect.apk',
        scrollOffset: 100,
        animationThreshold: 0.1
    };

    // ===== Mobile Navigation Toggle =====
    if (navToggle && navMenu) {
        navToggle.addEventListener('click', function() {
            navToggle.classList.toggle('active');
            navMenu.classList.toggle('active');
            document.body.style.overflow = navMenu.classList.contains('active') ? 'hidden' : '';
        });

        // Close menu when clicking on a link
        navLinks.forEach(link => {
            link.addEventListener('click', function() {
                navToggle.classList.remove('active');
                navMenu.classList.remove('active');
                document.body.style.overflow = '';
            });
        });

        // Close menu when clicking outside
        document.addEventListener('click', function(e) {
            if (!navMenu.contains(e.target) && !navToggle.contains(e.target)) {
                navToggle.classList.remove('active');
                navMenu.classList.remove('active');
                document.body.style.overflow = '';
            }
        });
    }

    // ===== Header Scroll Effect =====
    let lastScroll = 0;
    window.addEventListener('scroll', function() {
        const currentScroll = window.pageYOffset;

        if (currentScroll > 50) {
            header.classList.add('scrolled');
        } else {
            header.classList.remove('scrolled');
        }

        lastScroll = currentScroll;
    });

    // ===== Smooth Scroll for Navigation Links =====
    navLinks.forEach(link => {
        link.addEventListener('click', function(e) {
            const href = this.getAttribute('href');

            if (href.startsWith('#')) {
                e.preventDefault();
                const target = document.querySelector(href);

                if (target) {
                    const headerHeight = header.offsetHeight;
                    const targetPosition = target.getBoundingClientRect().top + window.pageYOffset - headerHeight;

                    window.scrollTo({
                        top: targetPosition,
                        behavior: 'smooth'
                    });
                }
            }
        });
    });

    // ===== Download Button Click Handler =====
    if (downloadBtn) {
        downloadBtn.addEventListener('click', function(e) {
            e.preventDefault();

            // Track download attempt (you can add analytics here)
            console.log('Download initiated');

            // Show download started notification
            showNotification('Download started! Check your downloads folder.', 'success');

            // Trigger download
            const link = document.createElement('a');
            link.href = CONFIG.downloadUrl;
            link.download = 'construct-connect.apk';
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
        });
    }

    // ===== Notification System =====
    function showNotification(message, type = 'info') {
        // Remove existing notification
        const existing = document.querySelector('.notification');
        if (existing) {
            existing.remove();
        }

        // Determine icon based on type
        let icon = 'ℹ';
        if (type === 'success') icon = '✓';
        if (type === 'error') icon = '✕';

        // Create notification element
        const notification = document.createElement('div');
        notification.className = `notification notification-${type}`;
        notification.innerHTML = `
            <div class="notification-content">
                <span class="notification-icon">${icon}</span>
                <span class="notification-message">${message}</span>
                <button class="notification-close">&times;</button>
            </div>
        `;

        // Determine background color
        let bgColor = '#8264FA'; // info
        if (type === 'success') bgColor = '#28a745';
        if (type === 'error') bgColor = '#dc3545';

        // Add styles
        notification.style.cssText = `
            position: fixed;
            bottom: 20px;
            right: 20px;
            z-index: 10000;
            background: ${bgColor};
            color: white;
            padding: 16px 20px;
            border-radius: 12px;
            box-shadow: 0 8px 24px rgba(0, 0, 0, 0.2);
            animation: slideIn 0.3s ease;
            max-width: 90%;
            width: 350px;
        `;

        // Add animation keyframes
        if (!document.querySelector('#notification-styles')) {
            const styles = document.createElement('style');
            styles.id = 'notification-styles';
            styles.textContent = `
                @keyframes slideIn {
                    from { transform: translateX(100%); opacity: 0; }
                    to { transform: translateX(0); opacity: 1; }
                }
                @keyframes slideOut {
                    from { transform: translateX(0); opacity: 1; }
                    to { transform: translateX(100%); opacity: 0; }
                }
                .notification-content {
                    display: flex;
                    align-items: center;
                    gap: 12px;
                }
                .notification-icon {
                    width: 24px;
                    height: 24px;
                    background: rgba(255,255,255,0.2);
                    border-radius: 50%;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    font-size: 14px;
                }
                .notification-message {
                    flex: 1;
                    font-size: 14px;
                    font-weight: 500;
                }
                .notification-close {
                    background: none;
                    border: none;
                    color: white;
                    font-size: 20px;
                    cursor: pointer;
                    opacity: 0.8;
                    transition: opacity 0.2s;
                    padding: 0;
                    line-height: 1;
                }
                .notification-close:hover {
                    opacity: 1;
                }
            `;
            document.head.appendChild(styles);
        }

        document.body.appendChild(notification);

        // Close button handler
        notification.querySelector('.notification-close').addEventListener('click', function() {
            notification.style.animation = 'slideOut 0.3s ease forwards';
            setTimeout(() => notification.remove(), 300);
        });

        // Auto remove after 5 seconds
        setTimeout(() => {
            if (notification.parentElement) {
                notification.style.animation = 'slideOut 0.3s ease forwards';
                setTimeout(() => notification.remove(), 300);
            }
        }, 5000);
    }

    // ===== Scroll Animation for Elements =====
    const observerOptions = {
        root: null,
        rootMargin: '0px',
        threshold: CONFIG.animationThreshold
    };

    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.classList.add('animate-in');
                observer.unobserve(entry.target);
            }
        });
    }, observerOptions);

    // Add animation styles
    const animationStyles = document.createElement('style');
    animationStyles.textContent = `
        .animate-on-scroll {
            opacity: 0;
            transform: translateY(30px);
            transition: opacity 0.6s ease, transform 0.6s ease;
        }
        .animate-on-scroll.animate-in {
            opacity: 1;
            transform: translateY(0);
        }
        .animate-delay-1 { transition-delay: 0.1s; }
        .animate-delay-2 { transition-delay: 0.2s; }
        .animate-delay-3 { transition-delay: 0.3s; }
        .animate-delay-4 { transition-delay: 0.4s; }
        .animate-delay-5 { transition-delay: 0.5s; }
    `;
    document.head.appendChild(animationStyles);

    // Observe elements - include all elements with animate-on-scroll class
    const animatedElements = document.querySelectorAll('.animate-on-scroll, .feature-card, .skill-item, .stat-card, .install-steps li, .testimonial-card, .pro-category');
    animatedElements.forEach((el, index) => {
        if (!el.classList.contains('animate-on-scroll')) {
            el.classList.add('animate-on-scroll');
        }
        el.classList.add(`animate-delay-${(index % 5) + 1}`);
        observer.observe(el);
    });

    // Fallback: Make all animated elements visible after 2 seconds if not already animated
    setTimeout(() => {
        document.querySelectorAll('.animate-on-scroll').forEach(el => {
            if (!el.classList.contains('animate-in') && !el.classList.contains('animated')) {
                el.classList.add('animate-in');
            }
        });
    }, 2000);

    // ===== Active Navigation Link Highlighting =====
    const sections = document.querySelectorAll('section[id]');

    function highlightNav() {
        const scrollPos = window.pageYOffset + 150;

        sections.forEach(section => {
            const sectionTop = section.offsetTop;
            const sectionHeight = section.offsetHeight;
            const sectionId = section.getAttribute('id');
            const navLink = document.querySelector(`.nav-link[href="#${sectionId}"]`);

            if (navLink) {
                if (scrollPos >= sectionTop && scrollPos < sectionTop + sectionHeight) {
                    navLinks.forEach(link => link.classList.remove('active'));
                    navLink.classList.add('active');
                }
            }
        });
    }

    window.addEventListener('scroll', highlightNav);
    highlightNav();

    // ===== Copy Email on Click =====
    const emailLinks = document.querySelectorAll('a[href^="mailto:"]');
    emailLinks.forEach(link => {
        link.addEventListener('click', function(e) {
            // On mobile, let the default mailto behavior work
            if (window.innerWidth <= 768) return;

            e.preventDefault();
            const email = this.href.replace('mailto:', '');

            navigator.clipboard.writeText(email).then(() => {
                showNotification('Email copied to clipboard!', 'success');
            }).catch(() => {
                // Fallback: open mailto
                window.location.href = this.href;
            });
        });
    });

    // ===== Counter Animation for Stats =====
    function animateCounter(element, target, duration = 2000) {
        let start = 0;
        const increment = target / (duration / 16);
        const isDecimal = target % 1 !== 0;

        function updateCounter() {
            start += increment;
            if (start < target) {
                element.textContent = isDecimal ? start.toFixed(1) : Math.floor(start).toLocaleString();
                requestAnimationFrame(updateCounter);
            } else {
                element.textContent = isDecimal ? target.toFixed(1) : target.toLocaleString();
            }
        }

        updateCounter();
    }

    // Observe stats section for counter animation
    const statsSection = document.querySelector('.stats');
    if (statsSection) {
        const statsObserver = new IntersectionObserver((entries) => {
            entries.forEach(entry => {
                if (entry.isIntersecting) {
                    const statValues = entry.target.querySelectorAll('.stat-value');
                    statValues.forEach(stat => {
                        const text = stat.textContent;
                        const number = parseFloat(text.replace(/[^0-9.]/g, ''));
                        if (!isNaN(number)) {
                            const prefix = text.match(/^[^0-9]*/)?.[0] || '';
                            const suffix = text.match(/[^0-9.]*$/)?.[0] || '';
                            stat.textContent = prefix + '0' + suffix;

                            setTimeout(() => {
                                animateCounter(stat, number, 1500);
                                // Re-add prefix and suffix after animation
                                setTimeout(() => {
                                    stat.textContent = prefix + (number % 1 !== 0 ? number.toFixed(1) : number.toLocaleString()) + suffix;
                                }, 1600);
                            }, 200);
                        }
                    });
                    statsObserver.unobserve(entry.target);
                }
            });
        }, { threshold: 0.5 });

        statsObserver.observe(statsSection);
    }

    // ===== Parallax Effect for Hero =====
    const heroImage = document.querySelector('.hero-image');
    if (heroImage && window.innerWidth > 768) {
        window.addEventListener('scroll', function() {
            const scrolled = window.pageYOffset;
            const heroHeight = document.querySelector('.hero').offsetHeight;

            if (scrolled < heroHeight) {
                heroImage.style.transform = `translateY(${scrolled * 0.1}px)`;
            }
        });
    }

    // ===== Preload Critical Images =====
    function preloadImage(src) {
        return new Promise((resolve, reject) => {
            const img = new Image();
            img.onload = resolve;
            img.onerror = reject;
            img.src = src;
        });
    }

    // Preload logo
    preloadImage('logo.png').catch(() => console.log('Logo image not found'));

    // ===== Initialize =====
    console.log('Construct Connect website initialized');

    // Add loaded class to body for potential loading animations
    document.body.classList.add('loaded');
});

// ===== Service Worker Registration (for PWA support - optional) =====
if ('serviceWorker' in navigator) {
    window.addEventListener('load', function() {
        // Uncomment below to enable service worker
        // navigator.serviceWorker.register('/sw.js')
        //     .then(reg => console.log('Service Worker registered'))
        //     .catch(err => console.log('Service Worker registration failed'));
    });
}

