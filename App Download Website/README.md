# Construct Connect - App Download Website

A professional, fully responsive website for users to download the Construct Connect app.

## 🚀 Quick Start

### Deploying to Vercel (Recommended)

1. **Create a repository on GitHub**
   - Go to GitHub and create a new repository
   - Upload all files from this folder

2. **Deploy to Vercel**
   - Go to [vercel.com](https://vercel.com)
   - Sign in with your GitHub account
   - Click "New Project"
   - Import your GitHub repository
   - Click "Deploy"
   - Your site will be live in seconds!

### Deploying to Netlify (Alternative)

1. Go to [netlify.com](https://netlify.com)
2. Drag and drop this folder to deploy instantly
3. Or connect your GitHub repository

## 📁 File Structure

```
App Download Website/
├── index.html       # Main HTML file
├── styles.css       # All CSS styles
├── script.js        # JavaScript functionality
├── logo.png         # Your app logo (ADD THIS!)
├── package.json     # Project metadata
└── README.md        # This file
```

## ⚠️ IMPORTANT: Before Deploying

### 1. Add Your Logo
Place your app logo file as `logo.png` in this folder.

### 2. Update Download Link
Open `script.js` and find this line near the top:

```javascript
downloadUrl: 'https://github.com/YOUR_USERNAME/YOUR_REPO/releases/latest/download/construct-connect.apk'
```

Replace with your actual GitHub release APK URL, for example:
```javascript
downloadUrl: 'https://github.com/your-actual-username/construct-connect/releases/latest/download/construct-connect.apk'
```

### 3. Creating a GitHub Release (for APK download)

1. Go to your Android app's GitHub repository
2. Click on "Releases" on the right sidebar
3. Click "Create a new release"
4. Add a tag (e.g., `v1.0.0`)
5. Upload your APK file to the release
6. Publish the release
7. Copy the direct download link to your APK

## 🎨 Features

- ✅ Fully responsive design (mobile, tablet, desktop)
- ✅ Modern, professional UI with smooth animations
- ✅ Mobile navigation with hamburger menu
- ✅ Scroll animations
- ✅ Counter animations for statistics
- ✅ Copy email to clipboard functionality
- ✅ Download button with notification
- ✅ SEO optimized meta tags
- ✅ Fast loading (pure HTML/CSS/JS, no frameworks)

## 📱 Pages/Sections

1. **Hero Section** - Main call-to-action with app preview
2. **Trust Banner** - Key trust indicators
3. **Features** - Platform features and benefits
4. **Professionals** - Skills we're looking for
5. **Safety Notice** - Security messaging
6. **Download** - Main download section with installation instructions
7. **Stats** - Platform statistics
8. **About** - Company information and contact
9. **Footer** - Links and legal

## 🔧 Customization

### Colors
Edit the CSS variables at the top of `styles.css`:

```css
:root {
    --primary: #8264FA;        /* Main brand color */
    --primary-dark: #6B4FD8;   /* Darker variant */
    --primary-light: #A18DFC;  /* Lighter variant */
    /* ... more colors */
}
```

### Content
All content is in `index.html`. Edit sections as needed.

### Statistics
Update the stats in the HTML:
- Paid to Sellers: `$20,000+`
- Countries: `7+`
- Active Users: `1000+`
- Rating: `4.8`

## 📧 Contact

Email: constructconnect@tech-hub-support.me

## 📄 License

© 2024-2026 Construct Connect. All rights reserved.

