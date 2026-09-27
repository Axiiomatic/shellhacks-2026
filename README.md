## Inspiration

We wanted to build an app with a real impact in accessibility for disabled people in today's digital climate. Given half of our team has had plenty of experience with computer vision (both in classes and personal projects), we decided we would focus on helping the visually impaired through the usage of industry machine learning and computer vision libraries.

## What it does

WalkThrough takes a constant video input from your device's front camera and uses Google's Machine Learning Kit for object detection through Bounding Boxes alongside OpenCV performing anchor tracking through corner and landmark detection, then combines the information from both to create a detailed depth perception and motion detection system that can detect when obstacles are getting too close to the user and send an appropriate warning. The app supports both directional audio cues based on the obstacle's placement relative to the camera as well as direct vibration cues.

Furthermore, WalkThrough also features an AI inference toggle that connects with the Gemini API and feeds it the information from both the camera feed and our detection systems to give the user more detailed descriptions, warnings and instructions for the user through Text-To-Speech. When AI inference is enabled, the app makes calls to the Gemini API every 5 seconds, with the important note that direct alerts from the base system take priority over Gemini's responses.

Finally, since the app _is_ geared towards the visually impaired, all the GUI elements were intentionally designed to be large and high contrast, and gesture based alternatives for all of the important toggles were also designed to ensure ease of use for any user, with TTS narration for confirmation of each option selected. 

### Gestures
- **Swipe Right**: Toggle Audio Alerts
- **Swipe Left**: Toggle Vibration Alerts
- **Double-tap**: Toggle Flashlight
- **Hold for 2 seconds**: Toggle AI Inference

## How we built it

The app was built in Android-Studio using Google's Machine Learning Vision example app as a base to build from, and adding all the additional systems on top of it using Java and Kotlin.

## Challenges we ran into

As always with hackathons, we ran out of time implementing the improvements we wanted to make. Lots of time spent iterating and building new systems, such as an AR powered 3D environment modelling tool using ARCore for accurate distance measurements and a more elaborate context and history system for faster responses from Gemini, ended up being wasted, since they couldn't be integrated into the final design without affecting the experience negatively. Of course, with more time we could sort out the issues, but as it stands we had to go with a more basic submission. Moreover, the depth tracking was hard to nail down without access to a proper sensor such as Lidar to map out the environment and only a camera feed to work with, needing to iterate through multiple methods to get a working warning system. 

## Accomplishments that we're proud of

We created a fully functional mobile app with no prior experience in mobile development, and managed to integrate multiple different systems into one seamless product experience and showed real promise for more advanced accessibility tools aimed at bridging the gap for disadvantaged communities through the use of emerging technologies.

## What we learned

Most of the libraries and tools used in this project had not been used by any of our team members before, meaning we got to learn a lot about mobile development through Android Studio, Google's own machine learning technologies, advanced computer vision techniques such as optical flow and anchor tracking, and AR libraries for more advanced modelling and environmental analysis (despite the last one going unused).

## What's next for WalkThrough
- Implementation of navigational maps and 3d mapping for more accurate navigational info.
- Improving the AI inference's speed and accuracy.
- Contact info alerts for emergency situations.
- Implementation of further sensors for more powerful mobile devices.
- Porting to other mobile and wearable devices such as smart watches or glasses.
