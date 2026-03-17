package com.flipper.psadecrypt

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Plein écran sans barre de status
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        setContentView(R.layout.activity_splash)

        val logo        = findViewById<View>(R.id.splash_logo)
        val halo        = findViewById<View>(R.id.splash_halo)
        val scanLine    = findViewById<View>(R.id.splash_scan_line)
        val content     = findViewById<View>(R.id.splash_content)
        val titleArf    = findViewById<TextView>(R.id.splash_title_arf)
        val titleComp   = findViewById<TextView>(R.id.splash_title_companion)
        val line        = findViewById<View>(R.id.splash_line)
        val tagline     = findViewById<TextView>(R.id.splash_tagline)
        val version     = findViewById<TextView>(R.id.splash_version)

        // Sécurité : si une vue est null on passe directement à MainActivity
        if (logo == null || halo == null || scanLine == null || content == null) {
            goToMain()
            return
        }

        runAnimation(logo, halo, scanLine, content, titleArf, titleComp, line, tagline, version)
    }

    private fun runAnimation(
        logo: View, halo: View, scanLine: View, content: View,
        titleArf: TextView?, titleComp: TextView?,
        line: View?, tagline: TextView?, version: TextView?
    ) {
        // === PHASE 1 (0ms) : scan line flash ===
        val scanFadeIn = ObjectAnimator.ofFloat(scanLine, "alpha", 0f, 0.6f).apply {
            duration = 200
        }
        val scanFadeOut = ObjectAnimator.ofFloat(scanLine, "alpha", 0.6f, 0f).apply {
            duration = 300
            startDelay = 200
        }
        AnimatorSet().apply {
            playSequentially(scanFadeIn, scanFadeOut)
            start()
        }

        // === PHASE 2 (300ms) : logo pop in + halo pulse ===
        handler.postDelayed({
            content.alpha = 1f

            // Logo : scale 0 -> 1.15 -> 1 (overshoot)
            val logoScaleX = ObjectAnimator.ofFloat(logo, "scaleX", 0f, 1.15f, 1f).apply {
                duration = 500
                interpolator = DecelerateInterpolator()
            }
            val logoScaleY = ObjectAnimator.ofFloat(logo, "scaleY", 0f, 1.15f, 1f).apply {
                duration = 500
                interpolator = DecelerateInterpolator()
            }
            val logoAlpha = ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f).apply {
                duration = 300
            }

            // Halo : scale 0.4->1.8, alpha 0->0.6->0 (pulse)
            val haloScale = ObjectAnimator.ofFloat(halo, "scaleX", 0.4f, 1.8f).apply {
                duration = 600
                interpolator = AccelerateDecelerateInterpolator()
            }
            val haloScaleY = ObjectAnimator.ofFloat(halo, "scaleY", 0.4f, 1.8f).apply {
                duration = 600
                interpolator = AccelerateDecelerateInterpolator()
            }
            val haloAlpha = ObjectAnimator.ofFloat(halo, "alpha", 0f, 0.7f, 0f).apply {
                duration = 600
            }

            AnimatorSet().apply {
                playTogether(logoScaleX, logoScaleY, logoAlpha, haloScale, haloScaleY, haloAlpha)
                start()
            }
        }, 300)

        // === PHASE 3 (700ms) : "ARF" apparaît avec slide up ===
        handler.postDelayed({
            titleArf?.let {
                val fadeIn = ObjectAnimator.ofFloat(it, "alpha", 0f, 1f).apply { duration = 400 }
                val slideUp = ObjectAnimator.ofFloat(it, "translationY", 30f, 0f).apply {
                    duration = 400
                    interpolator = DecelerateInterpolator()
                }
                AnimatorSet().apply { playTogether(fadeIn, slideUp); start() }
            }
        }, 700)

        // === PHASE 4 (950ms) : "COMPANION" apparaît ===
        handler.postDelayed({
            titleComp?.let {
                val fadeIn = ObjectAnimator.ofFloat(it, "alpha", 0f, 1f).apply { duration = 350 }
                val slideUp = ObjectAnimator.ofFloat(it, "translationY", 20f, 0f).apply {
                    duration = 350
                    interpolator = DecelerateInterpolator()
                }
                AnimatorSet().apply { playTogether(fadeIn, slideUp); start() }
            }
        }, 950)

        // === PHASE 5 (1150ms) : ligne rouge s'étend ===
        handler.postDelayed({
            line?.let {
                it.alpha = 0.7f
                val lineAnim = ValueAnimator.ofInt(0, 220).apply {
                    duration = 400
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { anim ->
                        val dp = anim.animatedValue as Int
                        val px = (dp * resources.displayMetrics.density).toInt()
                        it.layoutParams = it.layoutParams.also { lp -> lp.width = px }
                        it.requestLayout()
                    }
                }
                lineAnim.start()
            }
        }, 1150)

        // === PHASE 6 (1400ms) : tagline + version fade in ===
        handler.postDelayed({
            tagline?.let {
                ObjectAnimator.ofFloat(it, "alpha", 0f, 1f).apply {
                    duration = 500
                    start()
                }
            }
            version?.let {
                ObjectAnimator.ofFloat(it, "alpha", 0f, 1f).apply {
                    duration = 500
                    start()
                }
            }
        }, 1400)

        // === PHASE 7 (2400ms) : 2ème pulse halo + deuxième scan line ===
        handler.postDelayed({
            val haloScale2 = ObjectAnimator.ofFloat(halo, "scaleX", 1f, 2.2f).apply {
                duration = 500
                interpolator = AccelerateDecelerateInterpolator()
            }
            val haloScaleY2 = ObjectAnimator.ofFloat(halo, "scaleY", 1f, 2.2f).apply {
                duration = 500
            }
            val haloAlpha2 = ObjectAnimator.ofFloat(halo, "alpha", 0f, 0.5f, 0f).apply {
                duration = 500
            }
            AnimatorSet().apply { playTogether(haloScale2, haloScaleY2, haloAlpha2); start() }

            val scanFade2 = ObjectAnimator.ofFloat(scanLine, "alpha", 0f, 0.3f, 0f).apply {
                duration = 400
            }
            scanFade2.start()
        }, 2400)

        // === PHASE 8 (3000ms) : tout fade out → MainActivity ===
        handler.postDelayed({
            val fadeOut = ObjectAnimator.ofFloat(
                findViewById<View>(android.R.id.content), "alpha", 1f, 0f
            ).apply {
                duration = 350
            }
            fadeOut.start()
            handler.postDelayed({ goToMain() }, 350)
        }, 3000)
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        // Pas d'animation de transition — fondu géré par le splash lui-même
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
