from setuptools import setup, find_packages

setup(
    name="whisker",
    version="0.1.0",
    description="Whisker Home Server & CLI Client",
    packages=find_packages(),
    include_package_data=True,
    package_data={
        "whisker": ["*.html", "index.html"],
    },
    install_requires=[
        "requests",
        "aiortc",
        "pillow",
        "av",
    ],
    entry_points={
        "console_scripts": [
            "whisker=whisker.cli:main",
            "whisker-mcp=whisker.mcp:run_mcp_server",
        ],
    },
)
